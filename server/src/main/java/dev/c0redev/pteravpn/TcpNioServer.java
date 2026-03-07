package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.net.StandardSocketOptions;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class TcpNioServer implements AutoCloseable {
  private static final Logger log = Log.logger(TcpNioServer.class);

  private final Selector selector;
  private final TcpNioWorker[] workers;
  private final AtomicInteger rr = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final List<ServerSocketChannel> listeners = new ArrayList<>();
  private final Thread thread;

  TcpNioServer(Config cfg, UdpSessions udp, ExecutorService legacyExecutor) throws IOException {
    int count = Math.max(1, Runtime.getRuntime().availableProcessors());
    workers = new TcpNioWorker[count];
    for (int i = 0; i < count; i++) {
      workers[i] = new TcpNioWorker(cfg, udp, legacyExecutor);
    }
    log.info("NIO tcp workers " + count);
    selector = Selector.open();
    for (int port : cfg.listenPorts()) {
      ServerSocketChannel ss = ServerSocketChannel.open();
      ss.configureBlocking(false);
      ss.setOption(StandardSocketOptions.SO_REUSEADDR, true);
      ss.bind(new InetSocketAddress(port));
      ss.register(selector, SelectionKey.OP_ACCEPT);
      listeners.add(ss);
    }
    thread = new Thread(this::run, "tcp-nio-accept");
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) return;
    for (TcpNioWorker w : workers) {
      w.close();
    }
    selector.wakeup();
    try {
      thread.join();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    try {
      selector.close();
    } catch (IOException ignored) {}
    for (ServerSocketChannel ss : listeners) {
      try {
        ss.close();
      } catch (IOException ignored) {}
    }
    log.info("tcp nio server stopped");
  }

  private void run() {
    while (!closed.get()) {
      try {
        selector.select(1000);
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
          SelectionKey key = it.next();
          it.remove();
          if (!key.isValid() || !key.isAcceptable()) {
            continue;
          }
          accept((ServerSocketChannel) key.channel());
        }
      } catch (ClosedSelectorException ignored) {
        break;
      } catch (IOException ignored) {
      } catch (CancelledKeyException ignored) {}
    }
  }

  private void accept(ServerSocketChannel ss) throws IOException {
    while (true) {
      SocketChannel sc = ss.accept();
      if (sc == null) {
        return;
      }
      try {
        sc.configureBlocking(false);
        sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
        sc.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        pickWorker().register(sc);
      } catch (IOException e) {
        try {
          sc.close();
        } catch (IOException ignored) {}
      }
    }
  }

  private TcpNioWorker pickWorker() {
    int idx = Math.floorMod(rr.getAndIncrement(), workers.length);
    return workers[idx];
  }
}
