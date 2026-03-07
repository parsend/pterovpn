package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.Deque;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

final class UdpSessions implements AutoCloseable {
  private final Logger log = Log.logger(UdpSessions.class);
  private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
  private final UdpChannelWriter[] writers;
  private final Selector selector;
  private final Thread selectorThread;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final LongAdder opWriteEnable = new LongAdder();
  private final LongAdder opWriteDisable = new LongAdder();
  private final LongAdder opWriteFired = new LongAdder();
  private final LongAdder queueOverflow = new LongAdder();
  private final LongAdder queueDrops = new LongAdder();
  private final LongAdder udpQueueBytes = new LongAdder();
  private final LongAdder udpQueueFrames = new LongAdder();
  private final LongAdder udpBytesOut = new LongAdder();
  private final LongAdder udpBytesIn = new LongAdder();
  private final long metricsIntervalMs = 1000L;
  private long lastMetricsAt;
  private static final int UDP_BUFFER_SIZE = 64 * 1024;
  private static final int UDP_SESSION_QUEUE_CAPACITY = 1024;
  private static final int UDP_SESSION_QUEUE_BYTES = 1024 * 1024;
  private static final int UDP_CHANNEL_QUEUE_CAPACITY = 2048;

  UdpSessions(int channels) throws IOException {
    this.writers = new UdpChannelWriter[channels];
    this.selector = Selector.open();
    this.selectorThread = new Thread(this::selectLoop, "udp-selector");
    this.selectorThread.setDaemon(true);
    this.selectorThread.start();
    this.lastMetricsAt = System.currentTimeMillis();
  }

  void publishServerLog(String line) {
    if (line == null || line.isEmpty()) {
      return;
    }
    final String msg = "[UDP] " + line;
    for (UdpChannelWriter w : writers) {
      if (w == null) continue;
      w.sendLog(msg);
    }
  }

  private void publishUdpMetrics() {
    long now = System.currentTimeMillis();
    if ((now - lastMetricsAt) < metricsIntervalMs) {
      return;
    }
    long opWriteFiredDelta = opWriteFired.sumThenReset();
    long queueDropsDelta = queueDrops.sumThenReset();
    long bytesOutDelta = udpBytesOut.sumThenReset();
    long bytesInDelta = udpBytesIn.sumThenReset();
    long overflows = queueOverflow.sumThenReset();
    if (
      opWriteFiredDelta == 0 &&
      queueDropsDelta == 0 &&
      bytesOutDelta == 0 &&
      bytesInDelta == 0 &&
      overflows == 0 &&
      udpQueueFrames.sum() == 0 &&
      udpQueueBytes.sum() == 0
    ) {
      return;
    }
    long intervalMs = Math.max(1L, now - lastMetricsAt);
    long avgOutPerSec = bytesOutDelta * 1000L / intervalMs;
    long avgInPerSec = bytesInDelta * 1000L / intervalMs;
    long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    long memLimit = Runtime.getRuntime().maxMemory();
    String load = "-";
    try {
      load = String.format("%.2f", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    } catch (Throwable ignored) {}
    publishServerLog(
      "metrics opWriteEnable=" +
        opWriteEnable.sum() +
        " opWriteFire=" +
        opWriteFiredDelta +
        " opWriteDisable=" +
        opWriteDisable.sum() +
        " qSize=" +
        udpQueueFrames.sum() +
        "/" +
        udpQueueBytes.sum() +
        " qDrops=" +
        queueDropsDelta +
        " qOverflow=" +
        overflows +
        " in=" +
        bytesInDelta +
        " out=" +
        bytesOutDelta +
        " avgInPs=" +
        avgInPerSec +
        " avgOutPs=" +
        avgOutPerSec +
        " memMb=" +
        (mem / (1024 * 1024)) +
        "/" +
        (memLimit / (1024 * 1024)) +
        " load=" +
        load +
        " sessions=" +
        sessions.size()
    );
    lastMetricsAt = now;
  }

  void setWriter(int channelId, OutputStream out, Optional<Protocol.ClientOptions> opts) {
    if (channelId < 0 || channelId >= writers.length) throw new IllegalArgumentException("bad channel");
    UdpChannelWriter prev = writers[channelId];
    if (prev != null) {
      prev.close();
    }
    writers[channelId] = new UdpChannelWriter(channelId, out, opts);
  }

  void onFrame(int channelId, Protocol.UdpFrame f) throws IOException {
    Key k = new Key(f.addrType(), f.srcPort(), f.dst().getAddress(), f.dstPort());
    Session s = sessions.get(k);
    if (s == null) {
      Session created = createSession(channelId, k, f);
      Session raced = sessions.putIfAbsent(k, created);
      if (raced != null) {
        created.close();
        s = raced;
      } else {
        s = created;
      }
    }
    try {
      s.send(f.payload());
      udpBytesIn.add(f.payload().length);
    } catch (IOException e) {
      closeSession(k, s);
      throw e;
    }
  }

  private Session createSession(int channelId, Key k, Protocol.UdpFrame f) throws IOException {
    DatagramChannel dc = DatagramChannel.open();
    dc.configureBlocking(false);
    dc.setOption(StandardSocketOptions.SO_RCVBUF, 1 << 20);
    dc.setOption(StandardSocketOptions.SO_SNDBUF, 1 << 20);
    dc.connect(new InetSocketAddress(f.dst(), f.dstPort()));
    SelectionKey sk = dc.register(selector, SelectionKey.OP_READ);
    Session s = new Session(channelId, k, f.dst(), dc, sk);
    sk.attach(s);
    selector.wakeup();
    return s;
  }

  private void selectLoop() {
    ByteBuffer buf = ByteBuffer.allocateDirect(UDP_BUFFER_SIZE);
    while (!closed.get()) {
      try {
        int n = selector.select(200);
        if (n == 0) continue;
        for (SelectionKey k : selector.selectedKeys()) {
          if (!k.isValid()) continue;
          Object a = k.attachment();
          if (!(a instanceof Session s)) continue;
          try {
            if (k.isWritable()) {
              opWriteFired.increment();
              synchronized (s) {
                s.flushWrites();
              }
            }
            if (!k.isReadable()) continue;
            while (true) {
              buf.clear();
              int r = s.dc.read(buf);
              if (r < 0) {
                closeSession(s.key, s);
                break;
              }
              if (r == 0) break;
              buf.flip();
              byte[] payload = new byte[buf.remaining()];
              buf.get(payload);
              UdpChannelWriter w = writers[s.channelId];
              if (w == null) continue;
              w.send(new Protocol.UdpFrame(s.key.addrType, s.key.srcPort, s.dst, s.key.dstPort, payload));
              udpBytesOut.add(payload.length);
            }
          } catch (IOException e) {
            log.warning("udp read failed for key=" + s.key.srcPort + ":" + s.key.dstPort + " - " + e.getMessage());
            closeSession(s.key, s);
          }
        }
        selector.selectedKeys().clear();
        publishUdpMetrics();
      } catch (IOException e) {
        log.warning("udp selector error: " + e.getMessage());
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (!closed.compareAndSet(false, true)) return;
    for (Session s : sessions.values()) {
      try { s.close(); } catch (IOException ignored) {}
    }
    sessions.clear();
    for (UdpChannelWriter w : writers) {
      if (w == null) continue;
      w.close();
    }
    Arrays.fill(writers, null);
    try { selector.wakeup(); } catch (Exception ignored) {}
    try { selector.close(); } catch (Exception ignored) {}
  }

  private final class Session implements AutoCloseable {
    final int channelId;
    final Key key;
    final java.net.InetAddress dst;
    final DatagramChannel dc;
    final SelectionKey sk;
    private final Deque<ByteBuffer> pendingWrites = new ArrayDeque<>();
    private int pendingBytes;

    Session(int channelId, Key key, java.net.InetAddress dst, DatagramChannel dc, SelectionKey sk) {
      this.channelId = channelId;
      this.key = key;
      this.dst = dst;
      this.dc = dc;
      this.sk = sk;
    }

    void send(byte[] payload) throws IOException {
      ByteBuffer bb = ByteBuffer.wrap(payload);
      synchronized (this) {
        if (!pendingWrites.isEmpty()) {
          if (!queuePayload(bb)) {
            return;
          }
          requestWriteInterest();
          return;
        }

        while (bb.hasRemaining()) {
          int w = dc.write(bb);
          if (w > 0) {
            continue;
          }
          if (!queuePayload(bb)) {
            return;
          }
          requestWriteInterest();
          return;
        }
      }
    }

    void flushWrites() throws IOException {
      if (pendingWrites.isEmpty()) return;
      while (true) {
        ByteBuffer buf = pendingWrites.peekFirst();
        if (buf == null) break;
        int before = buf.remaining();
        int w = dc.write(buf);
        if (w <= 0) break;
        if (w >= before) {
          pendingWrites.pollFirst();
          pendingBytes -= before;
          udpQueueBytes.add(-before);
          udpQueueFrames.decrement();
          continue;
        }
        pendingBytes -= w;
        break;
      }
      if (pendingWrites.isEmpty()) {
        disableWriteInterest();
      }
    }

    private void requestWriteInterest() {
      if (!sk.isValid()) return;
      int ops = sk.interestOps();
      if ((ops & SelectionKey.OP_WRITE) != 0) return;
      sk.interestOps(ops | SelectionKey.OP_WRITE);
      opWriteEnable.increment();
      selector.wakeup();
    }

    private void disableWriteInterest() {
      if (!sk.isValid()) return;
      int ops = sk.interestOps();
      int nextOps = ops & ~SelectionKey.OP_WRITE;
      if (nextOps == ops) return;
      sk.interestOps(nextOps);
      opWriteDisable.increment();
    }

    private boolean queuePayload(ByteBuffer payload) {
      int remaining = payload.remaining();
      if (remaining <= 0) return true;
      int dropped = 0;
      while (
        (pendingWrites.size() >= UDP_SESSION_QUEUE_CAPACITY || pendingBytes + remaining > UDP_SESSION_QUEUE_BYTES)
          && !pendingWrites.isEmpty()
      ) {
        ByteBuffer removed = pendingWrites.pollFirst();
        if (removed == null) break;
        int removedBytes = removed.remaining();
        pendingBytes -= removedBytes;
        udpQueueBytes.add(-removedBytes);
        udpQueueFrames.decrement();
        queueDrops.increment();
        dropped++;
      }
      if (remaining > UDP_SESSION_QUEUE_BYTES) {
        log.warning("udp payload too large on key=" + key.srcPort + ":" + key.dstPort + ", drop packet");
        return false;
      }
      if (dropped > 0) {
        queueOverflow.increment();
        log.warning(
          "udp send queue overflow, dropped " + dropped + " packets on key=" +
            key.srcPort +
            ":" +
            key.dstPort
        );
      }
      pendingWrites.addLast(payload);
      pendingBytes += remaining;
      udpQueueBytes.add(remaining);
      udpQueueFrames.increment();
      return true;
    }

    @Override
    public void close() throws IOException {
      try {
        sk.cancel();
      } catch (Exception ignored) {}
      while (!pendingWrites.isEmpty()) {
        ByteBuffer removed = pendingWrites.pollFirst();
        if (removed == null) break;
        int removedBytes = removed.remaining();
        pendingBytes -= removedBytes;
        udpQueueBytes.add(-removedBytes);
        udpQueueFrames.decrement();
      }
      pendingWrites.clear();
      pendingBytes = 0;
      dc.close();
    }
  }

  private final class UdpChannelWriter implements AutoCloseable {
    private final int channelId;
    private final OutputStream out;
    private final Optional<Protocol.ClientOptions> opts;
    private final LinkedBlockingQueue<LoggableFrame> q;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread t;

    UdpChannelWriter(int channelId, OutputStream out, Optional<Protocol.ClientOptions> opts) {
      this.channelId = channelId;
      this.out = out;
      this.opts = opts != null ? opts : Optional.empty();
      this.q = new LinkedBlockingQueue<>(UDP_CHANNEL_QUEUE_CAPACITY);
      this.t = new Thread(this::loop, "udp-writer-" + channelId);
      this.t.setDaemon(true);
      this.t.start();
    }

    void send(Protocol.UdpFrame f) {
      if (closed.get()) return;
      if (f == null) return;
      enqueue(new UdpDataFrame(f));
    }

    void sendLog(String line) {
      if (closed.get()) return;
      if (line == null || line.isEmpty()) return;
      enqueue(new ServerLogFrame(line));
    }

    private void enqueue(LoggableFrame frame) {
      if (q.offer(frame)) return;
      LoggableFrame dropped = q.poll();
      if (dropped == null || !q.offer(frame)) {
        return;
      }
      log.warning(
        "udp channel writer " + channelId + " dropped one frame to keep queue size under limit"
      );
    }

    private void loop() {
      while (!closed.get()) {
        try {
          LoggableFrame frame = q.take();
          if (frame == null) continue;
          synchronized (out) {
            if (frame instanceof UdpDataFrame u) {
              int maxPad = opts.map(o -> o.padS4()).filter(p -> p > 0 && p <= 64).orElse(Protocol.MAX_PAD);
              Protocol.writeUdpFrame(out, u.frame(), maxPad);
              out.flush();
            } else if (frame instanceof ServerLogFrame l) {
              Protocol.writeServerLog(out, l.message());
            }
          }
        } catch (InterruptedException ignored) {
        } catch (IOException ignored) {
          closed.set(true);
        }
      }
    }

    @Override
    public void close() {
      closed.set(true);
      t.interrupt();
      try {
        t.join(5000);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }

    private sealed interface LoggableFrame permits UdpDataFrame, ServerLogFrame {}

    private static final class UdpDataFrame implements LoggableFrame {
      private final Protocol.UdpFrame frame;

      UdpDataFrame(Protocol.UdpFrame frame) {
        this.frame = frame;
      }

      Protocol.UdpFrame frame() {
        return frame;
      }
    }

    private static final class ServerLogFrame implements LoggableFrame {
      private final String message;

      ServerLogFrame(String message) {
        this.message = message;
      }

      String message() {
        return message;
      }
    }
  }

  private void closeSession(Key key, Session s) {
    Session removed = sessions.remove(key);
    if (removed != s) return;
    try {
      removed.close();
    } catch (IOException ignored) {}
  }

  private static final class Key {
    final byte addrType;
    final int srcPort;
    final byte[] dstIp;
    final int dstPort;

    Key(byte addrType, int srcPort, byte[] dstIp, int dstPort) {
      this.addrType = addrType;
      this.srcPort = srcPort;
      this.dstIp = dstIp;
      this.dstPort = dstPort;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Key k)) return false;
      return addrType == k.addrType && srcPort == k.srcPort && dstPort == k.dstPort && Arrays.equals(dstIp, k.dstIp);
    }

    @Override
    public int hashCode() {
      int r = Objects.hash(addrType, srcPort, dstPort);
      r = 31 * r + Arrays.hashCode(dstIp);
      return r;
    }
  }
}

