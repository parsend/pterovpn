package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class Config {
  private final List<Integer> listenPorts;
  private final String token;
  private final int udpChannels;
  private final int quicChannels;

  private Config(List<Integer> listenPorts, String token, int udpChannels, int quicChannels) {
    this.listenPorts = listenPorts;
    this.token = token;
    this.udpChannels = udpChannels;
    this.quicChannels = quicChannels;
  }

  List<Integer> listenPorts() {
    return listenPorts;
  }

  String token() {
    return token;
  }

  int udpChannels() {
    return udpChannels;
  }

  int quicChannels() {
    return quicChannels;
  }

  static Config load(Path configPath) throws IOException {
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(configPath)) {
      p.load(in);
    }

    String portsStr = firstNonEmpty(p.getProperty("listenPorts"), p.getProperty("listenPort"));
    if (portsStr == null) throw new IOException("listenPorts is required");
    List<Integer> ports = parsePortsCsv(portsStr);
    if (ports.isEmpty()) throw new IOException("listenPorts is empty");

    String token = firstNonEmpty(p.getProperty("token"), null);
    if (token == null) throw new IOException("token is required");
    if (token.length() > 4096) throw new IOException("token too long");

    int udpChannels = parseInt(p.getProperty("udpChannels"), 4);
    if (udpChannels != 4) throw new IOException("udpChannels must be 4");
    int quicChannels = parseInt(p.getProperty("quicChannels"), 4);
    if (quicChannels != 4) throw new IOException("quicChannels must be 4");

    return new Config(ports, token, udpChannels, quicChannels);
  }

  private static String firstNonEmpty(String a, String b) {
    if (a != null && !a.isBlank()) return a.trim();
    if (b != null && !b.isBlank()) return b.trim();
    return null;
  }

  private static int parseInt(String s, int def) throws IOException {
    if (s == null || s.isBlank()) return def;
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw new IOException("bad int: " + s, e);
    }
  }

  private static List<Integer> parsePortsCsv(String s) throws IOException {
    String[] parts = s.split(",");
    List<Integer> out = new ArrayList<>();
    for (String raw : parts) {
      String v = raw.trim();
      if (v.isEmpty()) continue;
      int port;
      try {
        port = Integer.parseInt(v);
      } catch (NumberFormatException e) {
        throw new IOException("bad port: " + v, e);
      }
      if (port < 1 || port > 65535) throw new IOException("bad port: " + port);
      if (!out.contains(port)) out.add(port);
    }
    return out;
  }
}

