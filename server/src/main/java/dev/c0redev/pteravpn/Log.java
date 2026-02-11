package dev.c0redev.pteravpn;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

final class Log {
  static Logger logger(Class<?> c) {
    Logger l = Logger.getLogger(c.getName());
    l.setUseParentHandlers(false);
    ConsoleHandler h = new ConsoleHandler();
    h.setLevel(Level.INFO);
    l.addHandler(h);
    l.setLevel(Level.INFO);
    return l;
  }
}

