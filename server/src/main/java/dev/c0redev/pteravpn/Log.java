package dev.c0redev.pteravpn;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

final class Log {
  private static boolean debug;

  static void setDebug(boolean on) {
    debug = on;
  }

  static Logger logger(Class<?> c) {
    Logger l = Logger.getLogger(c.getName());
    l.setUseParentHandlers(false);
    if (debug) {
      ConsoleHandler h = new ConsoleHandler();
      h.setLevel(Level.INFO);
      l.addHandler(h);
      l.setLevel(Level.INFO);
    } else {
      l.setLevel(Level.OFF);
    }
    return l;
  }
}

