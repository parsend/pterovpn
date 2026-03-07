package dev.c0redev.pteravpn;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.CancelledKeyException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class TcpNioWorker implements AutoCloseable, Runnable {
  private static final Logger log = Log.logger(TcpNioWorker.class);

  private final Config cfg;
  private final UdpSessions udp;
  private final ExecutorService legacyExecutor;
  private final Selector selector;
  private final ConcurrentLinkedQueue<SocketChannel> queue = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Thread thread;
  private final AtomicLong registered = new AtomicLong();

  TcpNioWorker(Config cfg, UdpSessions udp, ExecutorService legacyExecutor) throws IOException {
    this.cfg = cfg;
    this.udp = udp;
    this.legacyExecutor = legacyExecutor;
    selector = Selector.open();
    thread = new Thread(this, "tcp-nio-worker");
    thread.setDaemon(true);
    thread.start();
  }

  void register(SocketChannel client) {
    if (closed.get()) {
      try {
        client.close();
      } catch (IOException ignored) {}
      return;
    }
    queue.offer(client);
    selector.wakeup();
  }

  @Override
  public void run() {
    while (!closed.get()) {
      try {
        selector.select(1000);
        registerPending();
        long now = System.nanoTime();
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
          SelectionKey key = it.next();
          it.remove();
          if (!key.isValid()) {
            continue;
          }
          Object attachment = key.attachment();
          if (!(attachment instanceof TcpSession session)) {
            continue;
          }
          if (key.isConnectable()) {
            session.onConnectable();
          }
          if (key.isReadable()) {
            session.onReadable(key);
          }
          if (key.isWritable()) {
            session.onWritable(key);
          }
        }
        for (SelectionKey key : selector.keys()) {
          Object attachment = key.attachment();
          if (attachment instanceof TcpSession session) {
            session.onTick(now);
          }
        }
      } catch (ClosedSelectorException e) {
        break;
      } catch (IOException ignored) {}
    }
    closeAll();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    selector.wakeup();
    try {
      thread.join();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void registerPending() {
    SocketChannel client;
    while ((client = queue.poll()) != null) {
      try {
        SelectionKey key = client.register(selector, SelectionKey.OP_READ);
        key.attach(new TcpSession(key, cfg, udp, legacyExecutor));
        registered.incrementAndGet();
      } catch (IOException | CancelledKeyException e) {
        try {
          client.close();
        } catch (IOException ignored) {}
      }
    }
  }

  private void closeAll() {
    Set<SelectionKey> keys = selector.keys();
    for (SelectionKey key : keys) {
      Object attachment = key.attachment();
      if (attachment instanceof TcpSession session) {
        session.close();
      } else {
        try {
          key.cancel();
          key.channel().close();
        } catch (IOException ignored) {}
      }
    }
    try {
      selector.close();
    } catch (IOException ignored) {}
    log.fine("tcp-nio-worker stopped, sessions " + registered.get());
  }
}
