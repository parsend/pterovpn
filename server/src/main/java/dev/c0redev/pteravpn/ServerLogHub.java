package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class ServerLogHub {
  private static final Logger log = Log.logger(ServerLogHub.class);
  private static final Set<Subscription> subscribers = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private static final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "server-log-hub");
    t.setDaemon(true);
    return t;
  });
  private static final AtomicBoolean started = new AtomicBoolean(false);

  static {
    ticker.scheduleAtFixedRate(ServerLogHub::broadcastSnapshot, 3, 3, TimeUnit.SECONDS);
  }

  static Subscription subscribe(OutputStream out) {
    start();
    if (out == null) {
      return new Subscription(null);
    }
    Subscription s = new Subscription(out);
    subscribers.add(s);
    emit("log channel opened");
    return s;
  }

  static void emit(String line) {
    if (!started.get() || subscribers.isEmpty()) {
      return;
    }
    for (Subscription sub : subscribers.toArray(new Subscription[0])) {
      if (!sub.write(line)) {
        subscribers.remove(sub);
      }
    }
  }

  private static void broadcastSnapshot() {
    if (!started.get() || subscribers.isEmpty()) {
      return;
    }
    TcpSession.Snapshot ts = TcpSession.snapshot();
    UdpSessions.Stats us = UdpSessions.snapshot();
    emit("stat ts=%s tcp_active=%d tcp_created=%d tcp_tx=%d tcp_rx=%d udp_active=%d udp_in=%d udp_out=%d udp_loss=%d".formatted(
      Instant.now(),
      ts.active(),
      ts.created(),
      ts.txBytes(),
      ts.rxBytes(),
      us.active(),
      us.in(),
      us.out(),
      us.loss()
    ));
  }

  private static void start() {
    if (started.compareAndSet(false, true)) {
      log.fine("server log hub started");
    }
  }

  static final class Subscription implements AutoCloseable {
    private final OutputStream out;
    private final Object lock = new Object();
    private volatile boolean closed;

    Subscription(OutputStream out) {
      this.out = out;
    }

    boolean write(String line) {
      if (out == null) {
        return false;
      }
      if (closed) {
        return false;
      }
      byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
      try {
        synchronized (lock) {
          if (closed) {
            return false;
          }
          out.write(payload);
          out.flush();
        }
        return true;
      } catch (IOException ex) {
        return false;
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      subscribers.remove(this);
      if (out == null) {
        return;
      }
      try {
        out.close();
      } catch (IOException ignored) {
      }
    }
  }
}
