package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.net.StandardSocketOptions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class Main {
  private static Logger log;

  private static final Object RUN_LOCK = new Object();

  public static void main(String[] args) throws Exception {
    Path base = jarDir();
    Path cfgPath = base.resolve("config.properties");
    Config cfg = Config.load(cfgPath);
    Log.setDebug(cfg.debug());
    Log.setQuicTrace(cfg.quicTraceLog());
    log = Log.logger(Main.class);
    if (cfg.quicTraceLog()) {
      log.info("quicTraceLog=true — UDP до codec, parent QUIC, Netty QUIC FINEST, токены");
    }
    log.info("Base: " + base);
    log.info("Ports: " + cfg.listenPorts());
    log.info("Mode: " + cfg.serverMode());
    log.info("IPv6: " + (Ipv6Detect.hasIPv6() ? "available" : "not available"));
    if (cfg.updateEnabled()) {
      Path jp = jarPath();
      if (jp != null) {
        Thread t = new Thread(new UpdateRunner(jp, cfg), "update-runner");
        t.setDaemon(true);
        t.start();
      }
    }
    String host = cfg.publicHost();
    if (host != null && !host.isBlank()) {
      for (int port : cfg.listenPorts()) {
        log.info("Connection: " + host + ":" + port + ":" + cfg.token());
      }
    }

    int reactorThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    log.info("TCP reactor pool: " + reactorThreads + " threads");
    ExecutorService acceptPool = Executors.newCachedThreadPool();
    ExecutorService handshakePool = Executors.newFixedThreadPool(Math.max(16, reactorThreads * 2));
    ExecutorService streamPool = Executors.newCachedThreadPool();
    TcpReactorPool tcpPool = new TcpReactorPool(reactorThreads);
    List<ServerSocketChannel> sockets = Collections.synchronizedList(new ArrayList<>());
    final QuicServer[] quicHolder = new QuicServer[1];
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("Shutdown: closing TCP listeners and QUIC (release ports for next start)");
      List<ServerSocketChannel> copy;
      synchronized (sockets) {
        copy = new ArrayList<>(sockets);
      }
      for (ServerSocketChannel ss : copy) {
        try {
          ss.close();
        } catch (IOException ignored) {}
      }
      if (quicHolder[0] != null) {
        try {
          quicHolder[0].close();
        } catch (Exception ignored) {}
      }
      tcpPool.shutdown();
      streamPool.shutdown();
      handshakePool.shutdown();
      acceptPool.shutdown();
      awaitPool(acceptPool, "accept");
      awaitPool(handshakePool, "handshake");
      awaitPool(streamPool, "stream");
      synchronized (RUN_LOCK) {
        RUN_LOCK.notifyAll();
      }
    }, "pteravpn-shutdown"));

    try (UdpSessions udp = new UdpSessions(cfg.udpChannels())) {
      if (cfg.tcpEnabled()) {
        for (int port : cfg.listenPorts()) {
          ServerSocketChannel ss = ServerSocketChannel.open();
          ss.setOption(StandardSocketOptions.SO_REUSEADDR, true);
          ss.bind(new InetSocketAddress(port));
          sockets.add(ss);
          acceptPool.submit(() -> acceptLoop(ss, cfg, udp, handshakePool, streamPool, tcpPool));
        }
      }
      if (cfg.quicEnabled()) {
        quicHolder[0] = new QuicServer(cfg, udp, streamPool);
        quicHolder[0].start();
      }

      synchronized (RUN_LOCK) {
        RUN_LOCK.wait();
      }
    } finally {
      for (ServerSocketChannel ss : sockets) {
        try {
          ss.close();
        } catch (IOException ignored) {}
      }
      if (quicHolder[0] != null) {
        try {
          quicHolder[0].close();
        } catch (Exception ignored) {}
      }
      tcpPool.shutdown();
      streamPool.shutdown();
      handshakePool.shutdown();
      acceptPool.shutdown();
      awaitPool(acceptPool, "accept");
      awaitPool(handshakePool, "handshake");
      awaitPool(streamPool, "stream");
    }
  }

  private static void awaitPool(ExecutorService pool, String name) {
    try {
      if (!pool.awaitTermination(8, TimeUnit.SECONDS)) {
        pool.shutdownNow();
        if (!pool.awaitTermination(4, TimeUnit.SECONDS)) {
          log.warning("pool " + name + " did not terminate cleanly");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }

  private static void acceptLoop(
    ServerSocketChannel ss,
    Config cfg,
    UdpSessions udp,
    ExecutorService handshakePool,
    ExecutorService streamPool,
    TcpReactorPool tcpPool
  ) {
    while (ss.isOpen()) {
      try {
        SocketChannel s = ss.accept();
        s.setOption(StandardSocketOptions.TCP_NODELAY, true);
        s.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        handshakePool.submit(new ConnectionHandler(s.socket(), cfg, udp, tcpPool, streamPool));
      } catch (ClosedChannelException e) {
        break;
      } catch (IOException e) {
        if (!ss.isOpen()) {
          break;
        }
        String msg = e.getMessage();
        log.warning("accept error: " + (msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName()));
      }
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

  static Path jarPath() {
    try {
      String p = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
      return Paths.get(p).toAbsolutePath().normalize();
    } catch (Exception e) {
      return null;
    }
  }
}

