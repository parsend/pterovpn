package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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

    int pumpThreads = Math.min(512, Math.max(128, Runtime.getRuntime().availableProcessors() * 32));
    log.info("Pump pool: " + pumpThreads + " threads");
    ExecutorService pool = Executors.newCachedThreadPool();
    ExecutorService pumpPool = Executors.newFixedThreadPool(pumpThreads);
    try (UdpSessions udp = new UdpSessions(cfg.udpChannels())) {
      List<ServerSocket> sockets = new ArrayList<>();
      for (int port : cfg.listenPorts()) {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        sockets.add(ss);
        pool.submit(() -> acceptLoop(ss, cfg, udp, pool, pumpPool));
      }

      Thread.currentThread().join();
    } finally {
      pool.shutdown();
      pumpPool.shutdown();
    }
  }

  private static void acceptLoop(ServerSocket ss, Config cfg, UdpSessions udp, ExecutorService pool, ExecutorService pumpPool) {
    while (true) {
      try {
        Socket s = ss.accept();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        pool.submit(new ConnectionHandler(s, cfg, udp, pumpPool));
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
}

