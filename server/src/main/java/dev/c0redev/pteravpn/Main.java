package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.net.StandardSocketOptions;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;
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

    int cpu = Runtime.getRuntime().availableProcessors();
    int handlerThreads = Math.max(16, cpu * 8);
    if (handlerThreads > 512) {
      handlerThreads = 512;
    }
    log.info("Handler pool: " + handlerThreads + " threads");
    ExecutorService pool = Executors.newFixedThreadPool(handlerThreads);
    try (
      UdpSessions udp = new UdpSessions(cfg.udpChannels());
      TcpRelay tcpRelay = new TcpRelay(udp);
      Selector selector = Selector.open()
    ) {
      for (int port : cfg.listenPorts()) {
        ServerSocketChannel ss = ServerSocketChannel.open();
        ss.configureBlocking(false);
        ss.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ss.bind(new InetSocketAddress(port), 1024);
        ss.register(selector, SelectionKey.OP_ACCEPT);
        log.info("Listening port: " + port);
      }

      while (true) {
        try {
          selector.select();
        } catch (IOException e) {
          log.warning("selector wait error: " + e.getMessage());
          continue;
        }
        Set<SelectionKey> selected = selector.selectedKeys();
        Iterator<SelectionKey> it = selected.iterator();
        while (it.hasNext()) {
          SelectionKey key = it.next();
          it.remove();
          if (!key.isValid() || !key.isAcceptable()) {
            continue;
          }

          ServerSocketChannel ss = (ServerSocketChannel) key.channel();
          SocketChannel sc;
          while ((sc = ss.accept()) != null) {
            try {
              sc.configureBlocking(true);
              Socket s = sc.socket();
              s.setTcpNoDelay(true);
              s.setKeepAlive(true);
              pool.submit(new ConnectionHandler(s, cfg, udp, tcpRelay));
            } catch (Exception e) {
              try {
                sc.close();
              } catch (IOException ignored) {}
              log.warning("accept handling failed: " + e.getMessage());
            }
          }
        }
      }
    } finally {
      pool.shutdown();
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
