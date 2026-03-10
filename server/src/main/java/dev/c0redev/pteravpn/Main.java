package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    List<ServerSocketChannel> sockets = new ArrayList<>();
    try (UdpSessions udp = new UdpSessions(cfg.udpChannels())) {
      for (int port : cfg.listenPorts()) {
        ServerSocketChannel ss = ServerSocketChannel.open();
        ss.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ss.bind(new InetSocketAddress(port));
        sockets.add(ss);
        acceptPool.submit(() -> acceptLoop(ss, cfg, udp, handshakePool, streamPool, tcpPool));
      }

      Thread.currentThread().join();
    } finally {
      acceptPool.shutdown();
      handshakePool.shutdown();
      streamPool.shutdown();
      tcpPool.shutdown();
      for (ServerSocketChannel ss : sockets) {
        try {
          ss.close();
        } catch (IOException ignored) {}
      }
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
    while (true) {
      try {
        SocketChannel s = ss.accept();
        s.setOption(StandardSocketOptions.TCP_NODELAY, true);
        s.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        handshakePool.submit(new ConnectionHandler(s.socket(), cfg, udp, tcpPool, streamPool));
      } catch (IOException e) {
        log.warning("accept error: " + e.getMessage());
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

