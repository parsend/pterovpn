package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
  private final String kind;
  private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
  private final UdpChannelWriter[] writers;
  private final Selector selector;
  private final Thread selectorThread;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  UdpSessions(String kind, int channels) throws IOException {
    this.kind = kind;
    this.writers = new UdpChannelWriter[channels];
    this.selector = Selector.open();
    this.selectorThread = new Thread(this::selectLoop, kind + "-selector");
    this.selectorThread.setDaemon(true);
    this.selectorThread.start();
  }

  void setWriter(int channelId, OutputStream out) {
    if (channelId < 0 || channelId >= writers.length) throw new IllegalArgumentException("bad channel");
    writers[channelId] = new UdpChannelWriter(channelId, out, kind);
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
    s.send(f.payload());
  }

  private Session createSession(int channelId, Key k, Protocol.UdpFrame f) throws IOException {
    DatagramChannel dc = DatagramChannel.open();
    dc.configureBlocking(false);
    dc.connect(new InetSocketAddress(f.dst(), f.dstPort()));
    SelectionKey sk = dc.register(selector, SelectionKey.OP_READ);
    Session s = new Session(channelId, k, f.dst(), dc, sk);
    sk.attach(s);
    selector.wakeup();
    return s;
  }

  private void selectLoop() {
    ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024);
    while (!closed.get()) {
      try {
        int n = selector.select(1000);
        if (n == 0) continue;
        for (SelectionKey k : selector.selectedKeys()) {
          if (!k.isValid() || !k.isReadable()) continue;
          Object a = k.attachment();
          if (!(a instanceof Session s)) continue;
          buf.clear();
          int r = s.dc.read(buf);
          if (r <= 0) continue;
          buf.flip();
          byte[] payload = new byte[buf.remaining()];
          buf.get(payload);
          UdpChannelWriter w = writers[s.channelId];
          if (w == null) continue;
          w.send(new Protocol.UdpFrame(s.key.addrType, s.key.srcPort, s.dst, s.key.dstPort, payload));
        }
        selector.selectedKeys().clear();
      } catch (IOException e) {
        log.warning(kind + " selector error: " + e.getMessage());
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (!closed.compareAndSet(false, true)) return;
    for (Session s : sessions.values()) {
      try { s.close(); } catch (IOException ignored) {}
    }
    for (UdpChannelWriter w : writers) {
      if (w == null) continue;
      w.close();
    }
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
      dc.write(ByteBuffer.wrap(payload));
    }

    @Override
    public void close() throws IOException {
      try { sk.cancel(); } catch (Exception ignored) {}
      dc.close();
    }
  }

  private static final class UdpChannelWriter implements AutoCloseable {
    private final OutputStream out;
    private final LinkedBlockingQueue<Protocol.UdpFrame> q = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread t;

    UdpChannelWriter(int channelId, OutputStream out, String kind) {
      this.out = out;
      this.t = new Thread(this::loop, kind + "-writer-" + channelId);
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
            Protocol.writeUdpFrame(out, f);
            out.flush();
          }
        } catch (InterruptedException ignored) {
        } catch (IOException ignored) {
        }
      }
    }

    @Override
    public void close() {
      closed.set(true);
      t.interrupt();
    }
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

