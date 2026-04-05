package dev.c0redev.pteravpn;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

final class ActivePeerRegistry {

  private static final ConcurrentHashMap<String, Integer> refByHost = new ConcurrentHashMap<>();

  private ActivePeerRegistry() {}

  static void join(String hostKey) {
    if (hostKey == null || hostKey.isEmpty()) {
      return;
    }
    refByHost.merge(hostKey, 1, Integer::sum);
  }

  static void leave(String hostKey) {
    if (hostKey == null || hostKey.isEmpty()) {
      return;
    }
    refByHost.compute(
        hostKey,
        (k, v) -> {
          if (v == null) {
            return null;
          }
          int n = v - 1;
          return n <= 0 ? null : n;
        });
  }

  static int countDistinct() {
    return refByHost.size();
  }

  static String hostKey(SocketAddress a) {
    if (a instanceof InetSocketAddress ia) {
      if (ia.getAddress() != null) {
        return ia.getAddress().getHostAddress();
      }
    }
    return String.valueOf(a);
  }
}
