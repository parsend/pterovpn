package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.lang.management.ManagementFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

final class TcpRelay implements AutoCloseable {
  private static final Logger log = Log.logger(TcpRelay.class);
  private static final int BUF_SIZE = 64 * 1024;
  private static final int SELECT_TIMEOUT_MS = 200;
  private static final int STATS_INTERVAL_MS = 1000;

  private final RelayWorker[] workers;
  private final UdpSessions telemetryBus;

  TcpRelay() throws IOException {
    this(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), null);
  }

  TcpRelay(int workersCount) throws IOException {
    this(workersCount, null);
  }

  TcpRelay(UdpSessions telemetryBus) throws IOException {
    this(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), telemetryBus);
  }

  TcpRelay(int workersCount, UdpSessions telemetryBus) throws IOException {
    int count = Math.max(1, Math.min(8, workersCount));
    this.telemetryBus = telemetryBus;
    workers = new RelayWorker[count];
    for (int i = 0; i < count; i++) {
      workers[i] = new RelayWorker(i, this.telemetryBus);
    }
    log.info("tcp relay workers: " + count);
  }

  private void publishServerLog(String line) {
    if (telemetryBus == null || line == null || line.isEmpty()) {
      return;
    }
    telemetryBus.publishServerLog("[TCP] " + line);
  }

  void register(SocketChannel client, InetAddress ip, int port, XorStream xor) {
    RelayWorker w = workers[Math.floorMod(client.hashCode(), workers.length)];
    w.register(client, ip, port, xor);
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (RelayWorker w : workers) {
      try {
        w.close();
      } catch (IOException e) {
        failure = e;
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static final class RelayWorker implements AutoCloseable {
    private final Logger log = Log.logger(RelayWorker.class);
    private final Selector selector;
    private final Thread thread;
    private final BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
    private final UdpSessions telemetryBus;
    private final LongAdder opWriteEnable = new LongAdder();
    private final LongAdder opWriteDisable = new LongAdder();
    private final LongAdder opWriteFire = new LongAdder();
    private final LongAdder queueOverflow = new LongAdder();
    private final LongAdder remoteDrain = new LongAdder();
    private final LongAdder clientDrain = new LongAdder();
    private long lastMetricLog;
    private final int id;
    private volatile boolean stopped;

    RelayWorker(int id, UdpSessions telemetryBus) throws IOException {
      this.id = id;
      this.telemetryBus = telemetryBus;
      selector = Selector.open();
      lastMetricLog = System.currentTimeMillis();
      thread = new Thread(this::runLoop, "tcp-relay-" + id);
      thread.setDaemon(true);
      thread.start();
    }

    void register(SocketChannel client, InetAddress ip, int port, XorStream xor) {
      pending.add(
        () -> {
          try {
            doRegister(client, ip, port, xor);
          } catch (IOException e) {
            log.log(Level.WARNING, "register tcp session failed: " + e.getMessage(), e);
            closeChannel(client);
          }
        }
      );
      selector.wakeup();
    }

    private void doRegister(SocketChannel client, InetAddress ip, int port, XorStream xor) throws IOException {
      client.configureBlocking(false);
      client.setOption(StandardSocketOptions.TCP_NODELAY, true);
      client.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

      SocketChannel remote = SocketChannel.open();
      remote.configureBlocking(false);
      remote.setOption(StandardSocketOptions.TCP_NODELAY, true);
      remote.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
      boolean connected = remote.connect(new InetSocketAddress(ip, port));

      RelaySession s = new RelaySession(client, remote, xor);
      s.clientKey = client.register(selector, SelectionKey.OP_READ, new SessionKey(s, true));
      if (connected) {
        s.remoteConnected = true;
        s.remoteKey = remote.register(selector, SelectionKey.OP_READ, new SessionKey(s, false));
      } else {
        s.remoteKey = remote.register(selector, SelectionKey.OP_CONNECT, new SessionKey(s, false));
      }
      log.info("tcp relay session start from " + client + " to " + ip + ":" + port);
    }

    @Override
    public void close() throws IOException {
      stopped = true;
      selector.wakeup();
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      selector.close();
    }

    private void runLoop() {
      while (!stopped || !pending.isEmpty()) {
        try {
          runPending();
          selector.select(SELECT_TIMEOUT_MS);
          Iterator<SelectionKey> it = selector.selectedKeys().iterator();
          while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();

            if (!key.isValid()) {
              continue;
            }

            SessionKey sk = (SessionKey) key.attachment();
            if (sk == null) {
              continue;
            }

            RelaySession s = sk.session;
            if (s == null || s.closed) {
              continue;
            }

            if (key.isConnectable()) {
              onConnect(s, key);
              continue;
            }
            if (key.isReadable()) {
              if (sk.clientSide) {
                readClient(s, key);
              } else {
                readRemote(s, key);
              }
            }
            if (key.isWritable()) {
              opWriteFire.increment();
              if (sk.clientSide) {
                writeToClient(s, key);
              } else {
                writeToRemote(s, key);
              }
            }
          }
          maybePublishMetrics();
        } catch (Exception e) {
          log.log(Level.WARNING, "tcp relay loop error: " + e.getMessage(), e);
        }
      }

      closeAll();
    }

    private void maybePublishMetrics() {
      long now = System.currentTimeMillis();
      if ((now - lastMetricLog) < STATS_INTERVAL_MS) {
        return;
      }
      long writeFire = opWriteFire.sumThenReset();
      long queueOverflowDelta = queueOverflow.sumThenReset();
      long remoteDrainDelta = remoteDrain.sumThenReset();
      long clientDrainDelta = clientDrain.sumThenReset();
      long duration = Math.max(1L, now - lastMetricLog);
      long avgRemote = remoteDrainDelta * 1000L / duration;
      long avgClient = clientDrainDelta * 1000L / duration;
      long queueToRemote = 0;
      long queueToClient = 0;
      for (SelectionKey sk : selector.keys()) {
        SessionKey key = (SessionKey) sk.attachment();
        if (key == null || key.session == null) {
          continue;
        }
        if (key.clientSide) {
          queueToRemote += key.session.clientToRemote.position();
        } else {
          queueToClient += key.session.remoteToClient.position();
        }
      }
      if (
        writeFire == 0 &&
        queueOverflowDelta == 0 &&
        remoteDrainDelta == 0 &&
        clientDrainDelta == 0 &&
        queueToRemote == 0 &&
        queueToClient == 0
      ) {
        return;
      }
      long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      long memLimit = Runtime.getRuntime().maxMemory();
      String load = "-";
      try {
        load = String.format("%.2f", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
      } catch (Throwable ignored) {}
      String msg =
        "metrics worker=" +
        id +
        " opWrite=" +
        opWriteEnable.sum() +
        "+" +
        writeFire +
        " opWriteDisable=" +
        opWriteDisable.sum() +
        " overflows=" +
        queueOverflowDelta +
        " avgRemoteBps=" +
        avgRemote +
        " avgClientBps=" +
        avgClient +
        " queueToRemote=" +
        queueToRemote +
        " queueToClient=" +
        queueToClient +
        " memMb=" +
        (mem / (1024 * 1024)) +
        "/" +
        (memLimit / (1024 * 1024)) +
        " load=" +
        load;
      if (telemetryBus != null) {
        telemetryBus.publishServerLog(msg);
      }
      log.info(msg);
      lastMetricLog = now;
    }

    private void runPending() {
      Runnable r;
      while ((r = pending.poll()) != null) {
        r.run();
      }
    }

    private void onConnect(RelaySession s, SelectionKey remoteKey) {
      if (s.closed) return;
      try {
        if (!s.remote.finishConnect()) {
          return;
        }
        s.remoteConnected = true;
        int ops = SelectionKey.OP_READ;
        if (s.clientToRemote.position() > 0) {
          ops |= SelectionKey.OP_WRITE;
        }
        remoteKey.interestOps(ops);
      } catch (IOException e) {
        closeSession(s);
      }
    }

    private void readClient(RelaySession s, SelectionKey key) {
      try {
        if (s.clientToRemote.remaining() == 0) {
          queueOverflow.increment();
          disableInterest(key, SelectionKey.OP_READ);
          maybePublishMetrics();
          return;
        }

        int toRead = Math.min(s.tempIn.capacity(), s.clientToRemote.remaining());
        s.tempIn.clear();
        s.tempIn.limit(toRead);
        int n = s.client.read(s.tempIn);
        if (n <= 0) {
          if (n == -1) {
            closeSession(s);
          }
          return;
        }

        s.tempIn.flip();
        s.xor.decode(s.tempIn.array(), s.tempIn.position(), n);
        s.clientToRemote.put(s.tempIn.array(), s.tempIn.position(), n);
        if (s.remoteConnected) {
          enableInterest(s.remoteKey, SelectionKey.OP_WRITE);
        }
        if (s.clientToRemote.remaining() == 0) {
          disableInterest(key, SelectionKey.OP_READ);
        }
      } catch (Exception e) {
        closeSession(s);
      }
    }

    private void readRemote(RelaySession s, SelectionKey key) {
      if (!s.remoteConnected) return;
      if (s.remoteToClient.remaining() == 0) {
        disableInterest(key, SelectionKey.OP_READ);
        queueOverflow.increment();
        maybePublishMetrics();
        return;
      }

      try {
        int toRead = Math.min(s.tempOut.capacity(), s.remoteToClient.remaining());
        s.tempOut.clear();
        s.tempOut.limit(toRead);
        int n = s.remote.read(s.tempOut);
        if (n <= 0) {
          if (n == -1) {
            closeSession(s);
          }
          return;
        }

        s.tempOut.flip();
        s.xor.encode(s.tempOut.array(), s.tempOut.position(), n);
        s.remoteToClient.put(s.tempOut.array(), s.tempOut.position(), n);
        enableInterest(s.clientKey, SelectionKey.OP_WRITE);
        if (s.remoteToClient.remaining() == 0) {
          disableInterest(key, SelectionKey.OP_READ);
        }
      } catch (Exception e) {
        closeSession(s);
      }
    }

    private void writeToRemote(RelaySession s, SelectionKey key) {
      if (!s.remoteConnected) return;
      if (s.clientToRemote.position() == 0) {
        disableInterest(key, SelectionKey.OP_WRITE);
        return;
      }
      try {
        int n = write(s.remote, s.clientToRemote, false);
        if (n <= 0) return;
        if (s.clientToRemote.position() == 0) {
          disableInterest(key, SelectionKey.OP_WRITE);
          enableInterest(s.clientKey, SelectionKey.OP_READ);
        }
      } catch (Exception e) {
        closeSession(s);
      }
    }

    private void writeToClient(RelaySession s, SelectionKey key) {
      if (s.remoteToClient.position() == 0) {
        disableInterest(key, SelectionKey.OP_WRITE);
        return;
      }
      try {
        int n = write(s.client, s.remoteToClient, true);
        if (n <= 0) return;
        if (s.remoteToClient.position() == 0) {
          disableInterest(key, SelectionKey.OP_WRITE);
          enableInterest(s.remoteKey, SelectionKey.OP_READ);
        }
      } catch (Exception e) {
        closeSession(s);
      }
    }

    private int write(SocketChannel ch, ByteBuffer b, boolean toClient) throws IOException {
      b.flip();
      try {
        int n = ch.write(b);
        if (n > 0) {
          if (toClient) {
            clientDrain.add(n);
          } else {
            remoteDrain.add(n);
          }
        }
        b.compact();
        return n;
      } catch (IOException e) {
        b.compact();
        throw e;
      }
    }

    private void enableInterest(SelectionKey key, int op) {
      if (!key.isValid()) return;
      try {
        key.interestOps(key.interestOps() | op);
        if (op == SelectionKey.OP_WRITE) {
          opWriteEnable.increment();
        }
      } catch (CancelledKeyException ignored) {}
    }

    private void disableInterest(SelectionKey key, int op) {
      if (!key.isValid()) return;
      try {
        key.interestOps(key.interestOps() & ~op);
        if (op == SelectionKey.OP_WRITE) {
          opWriteDisable.increment();
        }
      } catch (CancelledKeyException ignored) {}
    }

    private void closeSession(RelaySession s) {
      if (s == null || s.closed) return;
      s.closed = true;
      closeKey(s.clientKey);
      closeKey(s.remoteKey);
      closeChannel(s.client);
      closeChannel(s.remote);
    }

    private void closeKey(SelectionKey key) {
      if (key != null) {
        key.cancel();
      }
    }

    private void closeChannel(SocketChannel ch) {
      try {
        if (ch != null) ch.close();
      } catch (IOException ignored) {}
    }

    private void closeAll() {
      for (SelectionKey key : selector.keys()) {
        SessionKey sk = (SessionKey) key.attachment();
        if (sk != null && sk.session != null) {
          closeSession(sk.session);
        }
      }
    }

    private static final class RelaySession {
      private final SocketChannel client;
      private final SocketChannel remote;
      private final XorStream xor;
      private SelectionKey clientKey;
      private SelectionKey remoteKey;
      private ByteBuffer clientToRemote = ByteBuffer.allocate(BUF_SIZE);
      private ByteBuffer remoteToClient = ByteBuffer.allocate(BUF_SIZE);
      private ByteBuffer tempIn = ByteBuffer.allocate(BUF_SIZE);
      private ByteBuffer tempOut = ByteBuffer.allocate(BUF_SIZE);
      private boolean remoteConnected;
      private boolean closed;

      RelaySession(SocketChannel client, SocketChannel remote, XorStream xor) {
        this.client = client;
        this.remote = remote;
        this.xor = xor;
      }
    }

    private static final class SessionKey {
      private final RelaySession session;
      private final boolean clientSide;

      SessionKey(RelaySession session, boolean clientSide) {
        this.session = session;
        this.clientSide = clientSide;
      }
    }
  }
}
