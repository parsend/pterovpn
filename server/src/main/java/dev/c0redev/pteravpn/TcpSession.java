package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class TcpSession {
  private static final Logger log = Log.logger(TcpSession.class);
  private static final long CONNECT_TIMEOUT_NANOS = 10_000_000_000L;
  private static final AtomicLong SESSION_SEQ = new AtomicLong();
  private static final AtomicLong SESSION_ACTIVE = new AtomicLong();
  private static final AtomicLong SESSION_CREATED = new AtomicLong();
  private static final AtomicLong TX_BYTES = new AtomicLong();
  private static final AtomicLong RX_BYTES = new AtomicLong();

  private final Config cfg;
  private final UdpSessions udp;
  private final ExecutorService legacyExecutor;
  private final long id = SESSION_SEQ.incrementAndGet();
  private final SelectionKey clientKey;
  private final SocketChannel client;
  private final Protocol.HandshakeParser handshakeParser = new Protocol.HandshakeParser();
  private final Protocol.TcpConnectParser connectParser = new Protocol.TcpConnectParser();
  private final ByteBuffer parseBuffer = ByteBuffer.allocate(64 * 1024);
  private final ByteBuffer c2r = ByteBuffer.allocate(64 * 1024);
  private final ByteBuffer r2c = ByteBuffer.allocate(64 * 1024);
  private final byte[] xorKey;
  private final byte[] tokenBytes;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private SelectionKey remoteKey;
  private SocketChannel remote;
  private Protocol.HandshakeResult handshake;
  private Protocol.TcpConnect tcpConnect;
  private int xorReadPos;
  private int xorWritePos;
  private State state = State.PARSING;
  private long connectDeadline;

  private enum State {
    PARSING,
    CONNECTING,
    RELAY,
    CLOSING
  }

  TcpSession(SelectionKey clientKey, Config cfg, UdpSessions udp, ExecutorService legacyExecutor) {
    this.clientKey = clientKey;
    this.client = (SocketChannel) clientKey.channel();
    this.cfg = cfg;
    this.udp = udp;
    this.legacyExecutor = legacyExecutor;
    this.xorKey = XorStream.keyFromToken(cfg.token());
    this.tokenBytes = cfg.token().getBytes(StandardCharsets.UTF_8);
    long created = SESSION_CREATED.incrementAndGet();
    SESSION_ACTIVE.incrementAndGet();
    log.fine("tcp session " + id + " registered, total " + created);
  }

  void onReadable(SelectionKey key) throws IOException {
    if (state == State.PARSING) {
      parseClientHandshake();
      return;
    }
    if (state != State.RELAY) {
      return;
    }
    if (key == clientKey) {
      readClientToRemote();
    } else if (key == remoteKey) {
      readRemoteToClient();
    }
  }

  void onWritable(SelectionKey key) throws IOException {
    if (state != State.RELAY) {
      return;
    }
    if (key == clientKey) {
      flushRemoteToClient();
    } else if (key == remoteKey) {
      flushClientToRemote();
    }
  }

  void onConnectable() throws IOException {
    if (state != State.CONNECTING || remote == null || remoteKey == null) {
      return;
    }
    if (System.nanoTime() > connectDeadline) {
      close();
      return;
    }
    if (!remote.finishConnect()) {
      close();
      return;
    }
    state = State.RELAY;
    remoteKey.interestOps(SelectionKey.OP_READ);
    refreshInterests();
    if (tcpConnect != null) {
      ServerLogHub.emit("ack tcp session %d remote=%s:%d".formatted(id, tcpConnect.ip().getHostAddress(), tcpConnect.port()));
    }
    if (c2r.hasRemaining()) {
      flushClientToRemote();
    }
  }

  void onTick(long now) {
    if (state == State.CONNECTING && now > connectDeadline) {
      close();
    }
  }

  private void parseClientHandshake() {
    try {
      int n = client.read(parseBuffer);
      if (n == -1) {
        close();
        return;
      }
      if (n == 0) {
        return;
      }
      int decodedStart = parseBuffer.position() - n;
      ByteBuffer decoded = parseBuffer.duplicate();
      decoded.position(decodedStart);
      decoded.limit(parseBuffer.position());
      xorReadPos = XorStream.xorInPlace(decoded, xorReadPos, xorKey);
      parseBuffer.flip();
      try {
        while (parseBuffer.hasRemaining() && state == State.PARSING) {
          if (handshake == null) {
            Protocol.HandshakeResult parsed = handshakeParser.read(parseBuffer);
            if (parsed == null) break;
            handshake = parsed;
            if (
              !MessageDigest.isEqual(
                tokenBytes,
                handshake.handshake().token().getBytes(StandardCharsets.UTF_8)
              )
            ) {
              close();
              return;
            }
            if (handshake.handshake().role() != Protocol.ROLE_TCP) {
              byte[] prefix = drainRemainder(parseBuffer);
              handoffToLegacy(prefix);
              return;
            }
            log.info("tcp session " + id + " role tcp from " + sockAddress());
            ServerLogHub.emit("ack tcp session %d from %s".formatted(id, sockAddress()));
            continue;
          }
          Protocol.TcpConnect c = connectParser.read(parseBuffer);
          if (c == null) break;
          tcpConnect = c;
          byte[] prefix = drainRemainder(parseBuffer);
          startRelay(prefix);
        }
      } finally {
        if (state == State.PARSING) {
          parseBuffer.compact();
        } else {
          parseBuffer.clear();
        }
      }
    } catch (IOException e) {
      log.fine("tcp parse error: " + e.getMessage());
      close();
    }
  }

  private byte[] drainRemainder(ByteBuffer src) {
    if (!src.hasRemaining()) {
      return new byte[0];
    }
    byte[] prefix = new byte[src.remaining()];
    src.get(prefix);
    return prefix;
  }

  private void handoffToLegacy(byte[] prefix) {
    if (!closed.compareAndSet(false, true)) return;
    state = State.CLOSING;
    SESSION_ACTIVE.decrementAndGet();
    ServerLogHub.emit("tcp session %d handoff role=%d".formatted(id, handshake.handshake().role()));
    try {
      clientKey.cancel();
      try {
        client.configureBlocking(true);
      } catch (IOException ignored) {
        closeSocket();
        return;
      }
      if (legacyExecutor == null) {
        closeSocket();
        return;
      }
      Protocol.HandshakeResult h = handshake;
      legacyExecutor.execute(new ConnectionHandler(client.socket(), cfg, udp, legacyExecutor, h, prefix));
    } catch (Exception ignored) {
      closeSocket();
    }
  }

  private void startRelay(byte[] pending) {
    if (tcpConnect == null || state != State.PARSING) {
      return;
    }
    try {
      remote = SocketChannel.open();
      remote.configureBlocking(false);
      remote.setOption(StandardSocketOptions.TCP_NODELAY, true);
      remote.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
      boolean connected = remote.connect(new InetSocketAddress(tcpConnect.ip(), tcpConnect.port()));
      connectDeadline = System.nanoTime() + CONNECT_TIMEOUT_NANOS;
      clientKey.interestOps(0);
      if (pending != null && pending.length > 0) {
        if (pending.length > c2r.capacity()) {
          close();
          return;
        }
        c2r.clear();
        c2r.put(pending);
        c2r.flip();
      }
      remoteKey = remote.register(clientKey.selector(), connected ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT, this);
      if (connected) {
        state = State.RELAY;
        refreshInterests();
        flushClientToRemote();
        log.info("tcp session " + id + " relayed to " + tcpConnect.ip().getHostAddress() + ":" + tcpConnect.port());
        ServerLogHub.emit("ack tcp session %d remote=%s:%d".formatted(id, tcpConnect.ip().getHostAddress(), tcpConnect.port()));
      } else {
        state = State.CONNECTING;
        ServerLogHub.emit("tcp session %d connecting to %s:%d".formatted(id, tcpConnect.ip().getHostAddress(), tcpConnect.port()));
        log.info("tcp session " + id + " connecting to " + tcpConnect.ip().getHostAddress() + ":" + tcpConnect.port());
      }
    } catch (IOException e) {
      log.fine("tcp connect init failed: " + e.getMessage());
      close();
    }
  }

  private void readClientToRemote() {
    try {
      if (c2r.hasRemaining()) return;
      c2r.clear();
      int n = client.read(c2r);
      if (n == -1) {
        close();
        return;
      }
      if (n == 0) return;
      c2r.flip();
      xorReadPos = XorStream.xorInPlace(c2r, xorReadPos, xorKey);
      flushClientToRemote();
    } catch (IOException e) {
      log.fine("tcp relay read from client failed: " + e.getMessage());
      close();
    }
  }

  private void readRemoteToClient() {
    try {
      if (r2c.hasRemaining()) return;
      r2c.clear();
      int n = remote.read(r2c);
      if (n == -1) {
        close();
        return;
      }
      if (n == 0) return;
      r2c.flip();
      xorWritePos = XorStream.xorInPlace(r2c, xorWritePos, xorKey);
      flushRemoteToClient();
    } catch (IOException e) {
      log.fine("tcp relay read from remote failed: " + e.getMessage());
      close();
    }
  }

  private void flushClientToRemote() {
    try {
      if (remote == null) return;
      while (c2r.hasRemaining()) {
        int n = remote.write(c2r);
        if (n > 0) TX_BYTES.addAndGet(n);
        if (n <= 0) break;
      }
      if (c2r.hasRemaining()) {
        if (remoteKey != null && remoteKey.isValid()) {
          remoteKey.interestOps(SelectionKey.OP_WRITE);
        }
        return;
      }
      c2r.clear();
      refreshInterests();
      if (r2c.hasRemaining()) {
        if (clientKey != null && clientKey.isValid()) {
          clientKey.interestOps(SelectionKey.OP_WRITE);
        }
      }
    } catch (IOException e) {
      log.fine("tcp relay write to remote failed: " + e.getMessage());
      close();
    }
  }

  private void flushRemoteToClient() {
    try {
      if (remote == null) return;
      while (r2c.hasRemaining()) {
        int n = client.write(r2c);
        if (n > 0) RX_BYTES.addAndGet(n);
        if (n <= 0) break;
      }
      if (r2c.hasRemaining()) {
        if (clientKey != null && clientKey.isValid()) {
          clientKey.interestOps(SelectionKey.OP_WRITE);
        }
        return;
      }
      r2c.clear();
      refreshInterests();
      if (c2r.hasRemaining()) {
        if (remoteKey != null && remoteKey.isValid()) {
          remoteKey.interestOps(SelectionKey.OP_WRITE);
        }
      }
    } catch (IOException e) {
      log.fine("tcp relay write to client failed: " + e.getMessage());
      close();
    }
  }

  private void refreshInterests() {
    if (state == State.CLOSING || !clientKey.isValid()) return;
    try {
      int clientOps = 0;
      if (state == State.RELAY) {
        if (!c2r.hasRemaining()) {
          clientOps |= SelectionKey.OP_READ;
        }
        if (r2c.hasRemaining()) {
          clientOps |= SelectionKey.OP_WRITE;
        }
      }
      clientKey.interestOps(clientOps);
      if (remoteKey == null || !remoteKey.isValid()) return;
      int remoteOps = 0;
      if (state == State.CONNECTING) {
        remoteOps = SelectionKey.OP_CONNECT;
      } else if (state == State.RELAY) {
        if (!r2c.hasRemaining()) {
          remoteOps |= SelectionKey.OP_READ;
        }
        if (c2r.hasRemaining()) {
          remoteOps |= SelectionKey.OP_WRITE;
        }
      }
      remoteKey.interestOps(remoteOps);
    } catch (Exception ignored) {}
  }

  void close() {
    if (!closed.compareAndSet(false, true)) return;
    state = State.CLOSING;
    if (remoteKey != null && remoteKey.isValid()) {
      remoteKey.cancel();
    }
    if (clientKey != null && clientKey.isValid()) {
      clientKey.cancel();
    }
    closeSocket();
    closeRemote();
    long active = SESSION_ACTIVE.decrementAndGet();
    log.fine(
      "tcp session " + id + " closed, active " + active +
      ", tx " + TX_BYTES.get() + ", rx " + RX_BYTES.get()
    );
    ServerLogHub.emit("tcp session %d closed tx=%d rx=%d".formatted(id, TX_BYTES.get(), RX_BYTES.get()));
  }

  static Snapshot snapshot() {
    return new Snapshot(SESSION_SEQ.get(), SESSION_ACTIVE.get(), SESSION_CREATED.get(), TX_BYTES.get(), RX_BYTES.get());
  }

  static final class Snapshot {
    private final long seq;
    private final long active;
    private final long created;
    private final long txBytes;
    private final long rxBytes;

    Snapshot(long seq, long active, long created, long txBytes, long rxBytes) {
      this.seq = seq;
      this.active = active;
      this.created = created;
      this.txBytes = txBytes;
      this.rxBytes = rxBytes;
    }

    long seq() {
      return seq;
    }

    long active() {
      return active;
    }

    long created() {
      return created;
    }

    long txBytes() {
      return txBytes;
    }

    long rxBytes() {
      return rxBytes;
    }
  }

  private String sockAddress() {
    try {
      return client.getRemoteAddress().toString();
    } catch (IOException ignored) {
      return "unknown";
    }
  }

  private void closeSocket() {
    try {
      client.close();
    } catch (IOException ignored) {}
  }

  private void closeRemote() {
    try {
      if (remote != null) {
        remote.close();
      }
    } catch (IOException ignored) {}
  }
}
