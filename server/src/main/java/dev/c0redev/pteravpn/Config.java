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
  private final String publicHost;
  private final boolean debug;
  private final boolean updateEnabled;
  private final String updateRepo;
  private final int updateCheckIntervalMinutes;
  private final int updateRestartExitCode;
  private final String serverMode;
  private final int quicListenPort;
  private final String quicCertPath;
  private final String quicKeyPath;
  private final String quicAlpn;
  private final int quicMaxStreams;
  private final int quicMaxHandshakes;
  private final int quicIdleTimeoutMs;
  private final int quicHandshakeTimeoutMs;
  private final int quicTcpConnectTimeoutMs;
  private final int quicIngressRingSlots;

  private final boolean quicTraceLog;

  private Config(List<Integer> listenPorts, String token, int udpChannels, String publicHost, boolean debug,
                 boolean updateEnabled, String updateRepo, int updateCheckIntervalMinutes, int updateRestartExitCode,
                 String serverMode, int quicListenPort, String quicCertPath, String quicKeyPath,
                 String quicAlpn, int quicMaxStreams, int quicMaxHandshakes, int quicIdleTimeoutMs,
                 int quicHandshakeTimeoutMs, int quicTcpConnectTimeoutMs, int quicIngressRingSlots,
                 boolean quicTraceLog) {
    this.listenPorts = listenPorts;
    this.token = token;
    this.udpChannels = udpChannels;
    this.publicHost = publicHost != null ? publicHost : "";
    this.debug = debug;
    this.updateEnabled = updateEnabled;
    this.updateRepo = updateRepo != null ? updateRepo.trim() : "";
    this.updateCheckIntervalMinutes = updateCheckIntervalMinutes;
    this.updateRestartExitCode = updateRestartExitCode;
    this.serverMode = serverMode;
    this.quicListenPort = quicListenPort;
    this.quicCertPath = quicCertPath != null ? quicCertPath.trim() : "";
    this.quicKeyPath = quicKeyPath != null ? quicKeyPath.trim() : "";
    this.quicAlpn = quicAlpn != null ? quicAlpn.trim() : "pteravpn";
    this.quicMaxStreams = quicMaxStreams;
    this.quicMaxHandshakes = quicMaxHandshakes;
    this.quicIdleTimeoutMs = quicIdleTimeoutMs;
    this.quicHandshakeTimeoutMs = quicHandshakeTimeoutMs;
    this.quicTcpConnectTimeoutMs = quicTcpConnectTimeoutMs;
    this.quicIngressRingSlots = quicIngressRingSlots;
    this.quicTraceLog = quicTraceLog;
  }

  String serverMode() { return serverMode; }
  int quicListenPort() { return quicListenPort; }
  String quicCertPath() { return quicCertPath; }
  String quicKeyPath() { return quicKeyPath; }
  String quicAlpn() { return quicAlpn; }
  int quicMaxStreams() { return quicMaxStreams; }
  int quicMaxHandshakes() { return quicMaxHandshakes; }
  int quicIdleTimeoutMs() { return quicIdleTimeoutMs; }
  int quicHandshakeTimeoutMs() { return quicHandshakeTimeoutMs; }
  int quicTcpConnectTimeoutMs() { return quicTcpConnectTimeoutMs; }
  int quicIngressRingSlots() { return quicIngressRingSlots; }
  boolean quicTraceLog() { return quicTraceLog; }
  boolean tcpEnabled() { return !"quic-only".equals(serverMode); }
  boolean quicEnabled() { return !"tcp-only".equals(serverMode); }

  boolean updateEnabled() { return updateEnabled; }
  String updateRepo() { return updateRepo; }
  int updateCheckIntervalMinutes() { return updateCheckIntervalMinutes; }
  int updateRestartExitCode() { return updateRestartExitCode; }

  boolean debug() {
    return debug;
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

  String publicHost() {
    return publicHost;
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

    String publicHost = firstNonEmpty(p.getProperty("publicHost"), "");
    boolean debug = "true".equalsIgnoreCase(p.getProperty("debug", "").trim());
    boolean updateEnabled = "true".equalsIgnoreCase(p.getProperty("update.enabled", "false").trim());
    String updateRepo = firstNonEmpty(p.getProperty("update.repo"), "unitdevgcc/pterovpn");
    int updateCheckIntervalMinutes = Math.max(15, parseInt(p.getProperty("update.checkIntervalMinutes"), 60));
    int updateRestartExitCode = parseInt(p.getProperty("update.restartExitCode"), 1);
    String serverMode = firstNonEmpty(p.getProperty("serverMode"), "tcp-only").toLowerCase();
    if (!serverMode.equals("tcp-only") && !serverMode.equals("quic-only") && !serverMode.equals("both")) {
      throw new IOException("bad serverMode: " + serverMode);
    }
    int quicListenPort = parseInt(p.getProperty("quicListenPort"), 0);
    String quicCertPath = firstNonEmpty(p.getProperty("quicCertPath"), "");
    String quicKeyPath = firstNonEmpty(p.getProperty("quicKeyPath"), "");
    String quicAlpn = firstNonEmpty(p.getProperty("quicAlpn"), "pteravpn");

    int quicMaxStreams = Math.max(0, parseInt(p.getProperty("quicMaxStreams"), 128));
    int quicMaxHandshakes = Math.max(0, parseInt(p.getProperty("quicMaxHandshakes"), 512));
    int quicIdleTimeoutMs = Math.max(0, parseInt(p.getProperty("quicIdleTimeoutMs"), 900_000));
    int quicHandshakeTimeoutMs = Math.max(0, parseInt(p.getProperty("quicHandshakeTimeoutMs"), 60_000));
    int quicTcpConnectTimeoutMs = Math.max(1_000, parseInt(p.getProperty("quicTcpConnectTimeoutMs"), 10_000));
    int quicIngressRingSlots = Math.max(64, parseInt(p.getProperty("quicIngressRingSlots"), 4096));
    boolean quicTraceLog = "true".equalsIgnoreCase(p.getProperty("quicTraceLog", "").trim());
    if (serverMode.equals("quic-only") || serverMode.equals("both")) {
      if (quicListenPort < 1 || quicListenPort > 65535) throw new IOException("bad quicListenPort");
      if (quicCertPath == null || quicCertPath.isBlank()) throw new IOException("quicCertPath is required");
      if (quicKeyPath == null || quicKeyPath.isBlank()) throw new IOException("quicKeyPath is required");
    }
    return new Config(ports, token, udpChannels, publicHost != null ? publicHost : "", debug,
        updateEnabled, updateRepo, updateCheckIntervalMinutes, updateRestartExitCode,
        serverMode, quicListenPort, quicCertPath, quicKeyPath, quicAlpn, quicMaxStreams, quicMaxHandshakes, quicIdleTimeoutMs,
        quicHandshakeTimeoutMs, quicTcpConnectTimeoutMs, quicIngressRingSlots,
        quicTraceLog);
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

