package dev.c0redev.pteravpn;

import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

final class Log {
  private static boolean debug;
  private static boolean quicTrace;
  private static volatile boolean julReady;

  static void setDebug(boolean on) {
    debug = on;
  }

  static void setQuicTrace(boolean on) {
    quicTrace = on;
  }

  static boolean quicTrace() {
    return quicTrace;
  }

  
  static synchronized void configureJul() {
    if (julReady) {
      return;
    }
    julReady = true;

    Logger root = Logger.getLogger("");
    root.setUseParentHandlers(false);
    for (var h : root.getHandlers()) {
      root.removeHandler(h);
    }
    ConsoleHandler ch = new ConsoleHandler();
    if (quicTrace) {
      ch.setLevel(Level.ALL);
    } else if (debug) {
      ch.setLevel(Level.INFO);
    } else {
      ch.setLevel(Level.WARNING);
    }
    root.addHandler(ch);
    root.setLevel(Level.ALL);

    InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);

    if (quicTrace) {
      Logger.getLogger("io.netty.incubator.codec.quic").setLevel(Level.FINEST);
      Logger.getLogger("io.netty.handler.ssl").setLevel(Level.FINE);
      Logger.getLogger("io.netty.channel.nio").setLevel(Level.FINE);
      Logger.getLogger("dev.c0redev.pteravpn").setLevel(Level.FINEST);
    }
  }

  static Logger logger(Class<?> c) {
    configureJul();
    Logger l = Logger.getLogger(c.getName());
    l.setUseParentHandlers(true);
    if (!debug && !quicTrace) {
      l.setLevel(Level.OFF);
    } else if (quicTrace) {
      l.setLevel(Level.FINEST);
    } else {
      l.setLevel(Level.INFO);
    }
    return l;
  }
}
