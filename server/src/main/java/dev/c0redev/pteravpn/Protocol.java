package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Optional;

final class Protocol {
  static final byte[] TRANSPORT_MAGIC = "PTTR".getBytes(StandardCharsets.UTF_8);
  static final byte TRANSPORT_VERSION = 1;
  static final byte TRANSPORT_XOR = 1;
  static final byte TRANSPORT_MTLS = 2;
  static final byte[] MAGIC = "PTVPN".getBytes(StandardCharsets.UTF_8);
  static final byte VERSION = 1;
  static final byte ROLE_UDP = 1;
  static final byte ROLE_TCP = 2;
  static final byte ROLE_BOOTSTRAP = 3;
  static final byte MSG_UDP = 1;
  static final byte ADDR_V4 = 4;
  static final byte ADDR_V6 = 6;
  static final int MAGIC_LEN = 5;
  static final int MAX_TOKEN = 4096;
  static final int MAX_FRAME = 64 * 1024 + 64;
  static final int MAX_PAD = 32;
  static final int MAX_OPTS = 512;

  static record HandshakeResult(Handshake handshake, Optional<ClientOptions> opts) {}

  static String readTransportPreface(InputStream in) throws IOException {
    byte[] buf = readN(in, TRANSPORT_MAGIC.length + 2);
    for (int i = 0; i < TRANSPORT_MAGIC.length; i++) {
      if (buf[i] != TRANSPORT_MAGIC[i]) throw new IOException("bad transport preface");
    }
    if (buf[TRANSPORT_MAGIC.length] != TRANSPORT_VERSION) throw new IOException("bad transport version");
    return switch (buf[TRANSPORT_MAGIC.length + 1]) {
      case TRANSPORT_XOR -> "xor";
      case TRANSPORT_MTLS -> "mtls";
      default -> throw new IOException("bad transport id");
    };
  }

  static HandshakeResult readHandshake(InputStream in) throws IOException {
    skipUntilMagic(in);
    Handshake hs = readHandshakeBody(in);
    Optional<ClientOptions> opts = readClientOptions(in);
    return new HandshakeResult(hs, opts);
  }

  static Optional<ClientOptions> readClientOptions(InputStream in) throws IOException {
    int optsLen;
    try {
      optsLen = readU16(in);
    } catch (EOFException e) {
      return Optional.empty();
    }
    if (optsLen <= 0) return Optional.empty();
    if (optsLen > MAX_OPTS) throw new IOException("bad opts len");
    byte[] buf = readN(in, optsLen);
    Optional<ClientOptions> parsed = ClientOptions.parse(new String(buf, StandardCharsets.UTF_8));
    if (parsed.isEmpty()) return Optional.empty();
    return parsed;
  }

  static void skipUntilMagic(InputStream in) throws IOException {
    byte[] buf = new byte[5];
    int n = 0;
    while (true) {
      int b = in.read();
      if (b == -1) throw new EOFException();
      System.arraycopy(buf, 1, buf, 0, 4);
      buf[4] = (byte) b;
      n++;
      if (n >= 5
          && buf[0] == MAGIC[0]
          && buf[1] == MAGIC[1]
          && buf[2] == MAGIC[2]
          && buf[3] == MAGIC[3]
          && buf[4] == MAGIC[4]) {
        return;
      }
    }
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

  record Handshake(byte role, int channelId, String token) {}
  record TcpConnect(byte addrType, InetAddress ip, int port) {}
  record UdpFrame(byte addrType, int srcPort, InetAddress dst, int dstPort, byte[] payload) {}

  record ClientOptions(int padS4) {
    static Optional<ClientOptions> parse(String json) {
      try {
        int padS4 = 32;
        if (json.contains("\"padS4\"")) {
          int i = json.indexOf("\"padS4\"");
          int start = json.indexOf(":", i) + 1;
          int end = json.indexOf(",", start);
          if (end < 0) end = json.indexOf("}", start);
          if (end < 0) end = json.length();
          padS4 = Integer.parseInt(json.substring(start, end).trim());
          if (padS4 < 0 || padS4 > 64) padS4 = 32;
        }
        return Optional.of(new ClientOptions(padS4));
      } catch (Exception e) {
        return Optional.empty();
      }
    }
  }
}
