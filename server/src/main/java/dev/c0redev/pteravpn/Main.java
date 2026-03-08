package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public final class Main {
  private static Logger log;

  public static void main(String[] args) throws Exception {
    Path base = jarDir();
    Path cfgPath = base.resolve("config.properties");
    Config cfg = Config.load(cfgPath);
    Log.setDebug(cfg.debug());
    log = Log.logger(Main.class);
    log.info("Base: " + base);
    log.info("Ports: " + cfg.listenPorts());
    String host = cfg.publicHost();
    if (host != null && !host.isBlank()) {
      for (int port : cfg.listenPorts()) {
        log.info("Connection: " + host + ":" + port + ":" + cfg.token());
      }
    }

    ExecutorService pool = Executors.newCachedThreadPool();
    try (UdpSessions udp = new UdpSessions(cfg.udpChannels())) {
      try (Selector selector = Selector.open()) {
      for (int port : cfg.listenPorts()) {
          ServerSocketChannel ss = ServerSocketChannel.open();
          ss.configureBlocking(false);
          ss.setOption(StandardSocketOptions.SO_REUSEADDR, true);
          ss.bind(new InetSocketAddress(port));
          ss.register(selector, SelectionKey.OP_ACCEPT);
      }
        acceptLoop(selector, cfg, udp, pool);
      }
    } finally {
      pool.shutdown();
    }
  }

  private static void acceptLoop(
    Selector selector,
    Config cfg,
    UdpSessions udp,
    ExecutorService pool
  ) throws IOException {
    while (true) {
      selector.select();
      for (SelectionKey key : selector.selectedKeys()) {
        if (!key.isValid()) {
          continue;
        }
        if (key.isAcceptable()) {
          accept(selector, key, cfg, udp);
          continue;
        }
        if (key.isReadable()) {
          readHandshake(key, pool);
        }
      }
      selector.selectedKeys().clear();
    }
  }

  private static void accept(
    Selector selector,
    SelectionKey key,
    Config cfg,
    UdpSessions udp
  ) {
    ServerSocketChannel ss = (ServerSocketChannel) key.channel();
    try {
      SocketChannel ch = ss.accept();
      if (ch == null) return;
      ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
      ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
      ch.configureBlocking(false);
      ch.register(selector, SelectionKey.OP_READ, new PendingConn(ch, cfg, udp));
    } catch (IOException e) {
      log.warning("accept error: " + e.getMessage());
    }
  }

  private static void readHandshake(SelectionKey key, ExecutorService pool) {
    PendingConn c = (PendingConn) key.attachment();
    try {
      if (c.read()) {
        key.cancel();
        c.handoff(pool);
      }
    } catch (Exception e) {
      key.cancel();
      c.close();
      log.warning("handshake error: " + e.getMessage());
    }
  }

  private static final class PendingConn {
    private final SocketChannel ch;
    private final Config cfg;
    private final UdpSessions udp;
    private final ProtocolHandshakeParser parser;
    private ByteBuffer readBuf = ByteBuffer.wrap(BufferPool.borrow(BufferPool.SMALL_CAP));
    private boolean bufferReturned;

    PendingConn(SocketChannel ch, Config cfg, UdpSessions udp) {
      this.ch = ch;
      this.cfg = cfg;
      this.udp = udp;
      this.parser = new ProtocolHandshakeParser(XorStream.keyFromToken(cfg.token()));
    }

    boolean read() throws IOException {
      int n = ch.read(readBuf);
      if (n == -1) {
        throw new IOException("peer closed");
      }
      if (n == 0) return false;
      readBuf.flip();
      boolean doneNow = parser.consume(readBuf);
      if (doneNow) {
        if (parser.result() == null || parser.needsClose()) {
          throw new IOException("bad handshake");
        }
        return true;
      }
      if (readBuf.hasRemaining()) {
        readBuf.compact();
      } else {
        readBuf.clear();
      }
      return false;
    }

    void handoff(ExecutorService pool) throws IOException {
      try {
        byte[] remainder = new byte[readBuf.remaining()];
        readBuf.get(remainder);
        returnBuffer();
        ch.configureBlocking(true);
        Protocol.HandshakeResult hr = parser.result();
        pool.submit(
          new ConnectionHandler(
            ch.socket(),
            cfg,
            udp,
            hr,
            remainder,
            parser.readPos()
          )
        );
      } catch (Exception e) {
        returnBuffer();
        try {
          ch.close();
        } catch (Exception ignored) {}
        throw e;
      }
    }

    void close() {
      returnBuffer();
      try {
        ch.close();
      } catch (IOException ignored) {
      }
    }

    private void returnBuffer() {
      if (bufferReturned) {
        return;
      }
      bufferReturned = true;
      BufferPool.release(readBuf.array());
    }
  }

  private static Path jarDir() {
    try {
      String p = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
      Path jar = Paths.get(p).toAbsolutePath().normalize();
      Path dir = jar.getParent();
      if (dir != null) return dir;
    } catch (Exception ignored) {
    }
    return Paths.get("").toAbsolutePath().normalize();
  }
}

