package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

final class Protocol {
  static final byte VERSION = 1;
  static final byte ROLE_UDP = 1;
  static final byte ROLE_TCP = 2;
  static final byte MSG_UDP = 1;
  static final byte ADDR_V4 = 4;
  static final byte ADDR_V6 = 6;
  static final int MAX_TOKEN = 4096;
  static final int MAX_FRAME = 64 * 1024 + 64;
  static final int MAX_PAD = 32;
  static final int MAX_OPTS = 512;
  static final int MAX_HELLO_CAPS_NONCE = 32;
  static final int CAPS_VERSION = 1;
  static final int TRANSPORT_TCP = 1;
  static final int TRANSPORT_QUIC = 1 << 1;
  static final int FEAT_IPV6 = 1;

  static record HandshakeResult(Handshake handshake, Optional<ClientOptions> opts) {}

  static HandshakeResult readHandshake(InputStream in) throws IOException {
    Handshake hs = readHandshakeBody(in);
    Optional<ClientOptions> opts = readClientOptions(in);
    return new HandshakeResult(hs, opts);
  }

  static Optional<ClientOptions> readClientOptions(InputStream in) throws IOException {
    int optsLen = readU16(in);
    if (optsLen == 0) return Optional.empty();
    if (optsLen > MAX_OPTS) throw new IOException("bad opts len");
    byte[] buf = readN(in, optsLen);
    Optional<ClientOptions> parsed = ClientOptions.parse(new String(buf, StandardCharsets.UTF_8));
    if (parsed.isEmpty()) throw new IOException("bad client options json");
    return parsed;
  }

  static Handshake readHandshakeBody(InputStream in) throws IOException {
    int ver = readU8(in);
    if (ver != VERSION) throw new IOException("bad version");
    byte role = (byte) readU8(in);
    int tokenLen = readU16(in);
    if (tokenLen < 0 || tokenLen > MAX_TOKEN) throw new IOException("bad token len");
    String token = new String(readN(in, tokenLen), StandardCharsets.UTF_8);
    int channelId = -1;
    if (role == ROLE_UDP) channelId = readU8(in);
    return new Handshake(role, channelId, token);
  }

  static TcpConnect readTcpConnect(InputStream in) throws IOException {
    return readTcpConnectBody(in);
  }

  static TcpConnect readTcpConnectBody(InputStream in) throws IOException {
    int at = readU8(in);
    InetAddress ip = readAddr(in, (byte) at);
    int port = readU16(in);
    return new TcpConnect((byte) at, ip, port);
  }

  static UdpFrame readUdpFrame(InputStream in) throws IOException {
    int flen = readU32(in);
    if (flen < 2 || flen > MAX_FRAME) throw new IOException("bad frame len");
    byte[] buf = readN(in, flen);
    if (buf[0] != MSG_UDP) throw new IOException("bad msg");
    byte at = buf[1];
    int off = 2;
    if (buf.length < off + 2) throw new IOException("short frame");
    int srcPort = ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    off += 2;
    int ipLen = at == ADDR_V6 ? 16 : 4;
    if (at != ADDR_V4 && at != ADDR_V6) throw new IOException("bad addr type");
    if (buf.length < off + ipLen + 2) throw new IOException("short frame");
    byte[] ipb = new byte[ipLen];
    System.arraycopy(buf, off, ipb, 0, ipLen);
    off += ipLen;
    InetAddress dst = InetAddress.getByAddress(ipb);
    int dstPort = ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    off += 2;
    int padLen = buf[buf.length - 1] & 0xff;
    if (padLen > 64 || buf.length - 1 - padLen < off) throw new IOException("bad pad len");
    int payEnd = buf.length - 1 - padLen;
    byte[] payload = new byte[payEnd - off];
    System.arraycopy(buf, off, payload, 0, payload.length);
    return new UdpFrame(at, srcPort, dst, dstPort, payload);
  }

  private static final SecureRandom RND = new SecureRandom();

  static void writeUdpFrame(OutputStream out, UdpFrame f) throws IOException {
    writeUdpFrame(out, f, MAX_PAD);
  }

  static void writeUdpFrame(OutputStream out, UdpFrame f, int maxPad) throws IOException {
    if (maxPad <= 0 || maxPad > 64) maxPad = MAX_PAD;
    int ipLen = f.addrType() == ADDR_V6 ? 16 : 4;
    int padLen = RND.nextInt(maxPad + 1);
    int payLen = f.payload().length;
    int flen = 1 + 1 + 2 + ipLen + 2 + payLen + padLen + 1;
    writeU32(out, flen);
    out.write(MSG_UDP);
    out.write(f.addrType());
    writeU16(out, f.srcPort());
    out.write(f.dst().getAddress());
    writeU16(out, f.dstPort());
    out.write(f.payload());
    byte[] pad = new byte[padLen];
    RND.nextBytes(pad);
    out.write(pad);
    out.write(padLen & 0xff);
    out.flush();
  }

  static InetAddress readAddr(InputStream in, byte addrType) throws IOException {
    return switch (addrType) {
      case ADDR_V4 -> InetAddress.getByAddress(readN(in, 4));
      case ADDR_V6 -> InetAddress.getByAddress(readN(in, 16));
      default -> throw new IOException("bad addr type");
    };
  }

  static byte[] readN(InputStream in, int n) throws IOException {
    byte[] b = new byte[n];
    int off = 0;
    while (off < n) {
      int r = in.read(b, off, n - off);
      if (r == -1) throw new EOFException();
      off += r;
    }
    return b;
  }

  static int readU8(InputStream in) throws IOException {
    int v = in.read();
    if (v == -1) throw new EOFException();
    return v;
  }

  static int readU16(InputStream in) throws IOException {
    int hi = readU8(in);
    int lo = readU8(in);
    return (hi << 8) | lo;
  }

  static int readU32(InputStream in) throws IOException {
    int b1 = readU8(in);
    int b2 = readU8(in);
    int b3 = readU8(in);
    int b4 = readU8(in);
    return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
  }

  static void writeU16(OutputStream out, int v) throws IOException {
    out.write((v >>> 8) & 0xff);
    out.write(v & 0xff);
  }

  static void writeU32(OutputStream out, int v) throws IOException {
    out.write((v >>> 24) & 0xff);
    out.write((v >>> 16) & 0xff);
    out.write((v >>> 8) & 0xff);
    out.write(v & 0xff);
  }

  static final int HELLO_CAPS_EXT_QUIC_LEAF_PIN = 1;

  static void writeServerHelloCaps(OutputStream out, ServerHelloCaps caps) throws IOException {
    out.write(caps.version() & 0xff);
    out.write(caps.legacyIpv6() & 0xff);
    out.write(caps.transportMask() & 0xff);
    writeU16(out, caps.featureBits());
    writeU16(out, caps.quicPort());
    writeU16(out, caps.tcpPortHint());
    out.write(caps.obfsProfileId() & 0xff);
    int nonceLen = caps.nonce() == null ? 0 : caps.nonce().length;
    if (nonceLen > MAX_HELLO_CAPS_NONCE) throw new IOException("caps nonce too long");
    out.write(nonceLen & 0xff);
    if (nonceLen > 0) out.write(caps.nonce());
    byte[] qp = caps.quicLeafPinSha256();
    if (qp != null && qp.length == 32) {
      out.write(HELLO_CAPS_EXT_QUIC_LEAF_PIN);
      out.write(qp);
    }
    out.flush();
  }

  static ServerHelloCaps readServerHelloCaps(InputStream in) throws IOException {
    int version = readU8(in);
    int legacyIpv6 = readU8(in);
    int transportMask = readU8(in);
    int featureBits = readU16(in);
    int quicPort = readU16(in);
    int tcpPortHint = readU16(in);
    int obfsProfileId = readU8(in);
    int nonceLen = readU8(in);
    if (nonceLen > MAX_HELLO_CAPS_NONCE) throw new IOException("caps nonce too long");
    byte[] nonce = nonceLen == 0 ? new byte[0] : readN(in, nonceLen);
    byte[] qpin = null;
    int ext = in.read();
    if (ext >= 0) {
      if (ext != HELLO_CAPS_EXT_QUIC_LEAF_PIN) {
        throw new IOException("bad caps extension tag: " + ext);
      }
      qpin = readN(in, 32);
    }
    return new ServerHelloCaps(
        version,
        legacyIpv6,
        transportMask,
        featureBits,
        quicPort,
        tcpPortHint,
        obfsProfileId,
        nonce,
        qpin);
  }

  record Handshake(byte role, int channelId, String token) {}
  record TcpConnect(byte addrType, InetAddress ip, int port) {}
  record UdpFrame(byte addrType, int srcPort, InetAddress dst, int dstPort, byte[] payload) {}
  record ServerHelloCaps(
      int version,
      int legacyIpv6,
      int transportMask,
      int featureBits,
      int quicPort,
      int tcpPortHint,
      int obfsProfileId,
      byte[] nonce,
      byte[] quicLeafPinSha256) {}

  record ClientOptions(
      int padS4,
      int capsVersion,
      int transportMask,
      int featureBits,
      long clientTsSec,
      byte[] clientNonce
  ) {
    static Optional<ClientOptions> parse(String json) {
      try {
        int padS4 = 32;
        String sPad = readJsonScalar(json, "padS4");
        if (sPad != null) {
          padS4 = Integer.parseInt(sPad);
          if (padS4 < 0 || padS4 > 64) padS4 = 32;
        }
        int capsVersion = parseIntOr(readJsonScalar(json, "capsVersion"), 0);
        int transportMask = parseIntOr(readJsonScalar(json, "transportMask"), 0);
        int featureBits = parseIntOr(readJsonScalar(json, "featureBits"), 0);
        long clientTsSec = parseLongOr(readJsonScalar(json, "clientTsSec"), 0L);
        byte[] clientNonce = parseNonce(readJsonScalar(json, "clientNonce"));
        return Optional.of(new ClientOptions(padS4, capsVersion, transportMask, featureBits, clientTsSec, clientNonce));
      } catch (Exception e) {
        return Optional.empty();
      }
    }

    private static String readJsonScalar(String json, String key) {
      String k = "\"" + key + "\"";
      int i = json.indexOf(k);
      if (i < 0) return null;
      int colon = json.indexOf(":", i + k.length());
      if (colon < 0) return null;
      int start = colon + 1;
      while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
      if (start >= json.length()) return null;
      if (json.charAt(start) == '"') {
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
      }
      int end = start;
      while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
      return json.substring(start, end).trim();
    }

    private static int parseIntOr(String s, int fallback) {
      if (s == null || s.isBlank()) return fallback;
      return Integer.parseInt(s);
    }

    private static long parseLongOr(String s, long fallback) {
      if (s == null || s.isBlank()) return fallback;
      return Long.parseLong(s);
    }

    private static byte[] parseNonce(String s) {
      if (s == null || s.isBlank()) return null;
      try {
        return Base64.getUrlDecoder().decode(s);
      } catch (Exception ignored) {}
      try {
        int n = s.length();
        if ((n & 1) != 0) return null;
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
          out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
      } catch (Exception ignored) {}
      return null;
    }
  }
}
