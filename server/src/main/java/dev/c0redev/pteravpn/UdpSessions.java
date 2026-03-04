package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.util.Optional;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class UdpSessions implements AutoCloseable {
  private final Logger log = Log.logger(UdpSessions.class);
  private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
  private final UdpChannelWriter[] writers;
  private final Selector selector;
  private final Thread selectorThread;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private static final int UDP_BUFFER_SIZE = 64 * 1024;
  private static final int WRITE_RETRY_DELAY_MS = 2;
  private static final int WRITE_TIMEOUT_MS = 2_000;

  UdpSessions(int channels) throws IOException {
    this.writers = new UdpChannelWriter[channels];
    this.selector = Selector.open();
    this.selectorThread = new Thread(this::selectLoop, "udp-selector");
    this.selectorThread.setDaemon(true);
    this.selectorThread.start();
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
        int n = selector.select(1000);
        if (n == 0) continue;
        for (SelectionKey k : selector.selectedKeys()) {
          if (!k.isValid() || !k.isReadable()) continue;
          Object a = k.attachment();
          if (!(a instanceof Session s)) continue;
          try {
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
            }
          } catch (IOException e) {
            log.warning("udp read failed for key=" + s.key.srcPort + ":" + s.key.dstPort + " - " + e.getMessage());
            closeSession(s.key, s);
          }
        }
        selector.selectedKeys().clear();
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

  private static final class Session implements AutoCloseable {
    final int channelId;
    final Key key;
    final java.net.InetAddress dst;
    final DatagramChannel dc;
    final SelectionKey sk;

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
        long timeoutAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WRITE_TIMEOUT_MS);
        while (bb.hasRemaining()) {
          int w = dc.write(bb);
          if (w > 0) continue;
          if (System.nanoTime() >= timeoutAt) throw new IOException("udp send timeout");
          try {
            TimeUnit.MILLISECONDS.sleep(WRITE_RETRY_DELAY_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("udp send interrupted", e);
          }
        }
      }
    }

    @Override
    public void close() throws IOException {
      try { sk.cancel(); } catch (Exception ignored) {}
      dc.close();
    }
  }

  private static final class UdpChannelWriter implements AutoCloseable {
    private final int channelId;
    private final OutputStream out;
    private final Optional<Protocol.ClientOptions> opts;
    private final LinkedBlockingQueue<Protocol.UdpFrame> q = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread t;

    UdpChannelWriter(int channelId, OutputStream out, Optional<Protocol.ClientOptions> opts) {
      this.channelId = channelId;
      this.out = out;
      this.opts = opts != null ? opts : Optional.empty();
      this.t = new Thread(this::loop, "udp-writer-" + channelId);
      this.t.setDaemon(true);
      this.t.start();
    }

    void send(Protocol.UdpFrame f) {
      if (closed.get()) return;
      q.offer(f);
    }

    private void loop() {
      while (!closed.get()) {
        try {
          Protocol.UdpFrame f = q.poll(1, TimeUnit.SECONDS);
          if (f == null) continue;
          synchronized (out) {
            int maxPad = opts.map(o -> o.padS4()).filter(p -> p > 0 && p <= 64).orElse(Protocol.MAX_PAD);
            Protocol.writeUdpFrame(out, f, maxPad);
            out.flush();
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

