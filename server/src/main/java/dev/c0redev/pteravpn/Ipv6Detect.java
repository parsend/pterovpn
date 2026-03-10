package dev.c0redev.pteravpn;

import java.net.Inet6Address;
import java.net.InetAddress;

final class Ipv6Detect {
  private static final boolean HAS_IPV6 = detect();

  static boolean hasIPv6() {
    return HAS_IPV6;
  }

  private static boolean detect() {
    try {
      InetAddress addr = InetAddress.getByName("2001:4860:4860::8888");
      if (!(addr instanceof Inet6Address)) return false;
      if (addr.isReachable(3000)) return true;
    } catch (Throwable ignored) {
    }
    return false;
  }
}
