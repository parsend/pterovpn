package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Optional;

final class Protocol {
  static final byte[] MAGIC = "PTVPN".getBytes(StandardCharsets.UTF_8);
  static final byte VERSION = 1;
  static final byte ROLE_UDP = 1;
  static final byte ROLE_TCP = 2;
  static final byte ROLE_LOG = 3;
  static final byte MSG_UDP = 1;
  static final byte ADDR_V4 = 4;
  static final byte ADDR_V6 = 6;
  static final int MAGIC_LEN = 5;
  static final int MAX_TOKEN = 4096;
  static final int MAX_FRAME = 64 * 1024 + 64;
  static final int MAX_PAD = 32;
  static final int MAX_OPTS = 512;

  static record HandshakeResult(Handshake handshake, Optional<ClientOptions> opts) {}

  static HandshakeResult readHandshake(InputStream in) throws IOException {
    skipUntilMagic(in);
    Handshake hs = readHandshakeBody(in);
    Optional<ClientOptions> opts = readClientOptions(in);
    return new HandshakeResult(hs, opts);
  }

  static Optional<ClientOptions> readClientOptions(InputStream in) throws IOException {
    if (!in.markSupported()) return Optional.empty();
    if (in.available() < 2) return Optional.empty();
    in.mark(MAX_OPTS + 4);
    int optsLen = readU16(in);
    if (optsLen <= 0 || optsLen > MAX_OPTS) {
      in.reset();
      return Optional.empty();
    }
    if (in.available() < optsLen) {
      in.reset();
      return Optional.empty();
    }
    byte[] buf = readN(in, optsLen);
    Optional<ClientOptions> parsed = ClientOptions.parse(new String(buf, StandardCharsets.UTF_8));
    if (parsed.isEmpty()) {
      in.reset();
      return Optional.empty();
    }
    return parsed;
  }

  static final class HandshakeParser {
    private enum Stage {
      SEEK_MAGIC,
      READ_VERSION,
      READ_ROLE,
      READ_TOKEN_LEN_HI,
      READ_TOKEN_LEN_LO,
      READ_TOKEN,
      READ_CHANNEL_ID,
      READ_OPTIONS,
      DONE
    }

    private Stage stage = Stage.SEEK_MAGIC;
    private int magicPos;
    private int tokenLen;
    private int tokenRead;
    private byte[] tokenBytes;
    private byte role;
    private int channelId;
    private Handshake hs;
    private Optional<ClientOptions> opts = Optional.empty();
    private HandshakeResult result;
    private int optsLen = -1;
    private int optsRead;
    private byte[] optsBytes;

    HandshakeResult read(ByteBuffer src) throws IOException {
      while (src.hasRemaining() && stage != Stage.DONE) {
        switch (stage) {
          case SEEK_MAGIC -> {
            byte b = src.get();
            if (b == MAGIC[magicPos]) {
              magicPos++;
              if (magicPos >= MAGIC_LEN) {
                stage = Stage.READ_VERSION;
                break;
              }
              continue;
            }
            magicPos = b == MAGIC[0] ? 1 : 0;
          }
          case READ_VERSION -> {
            int v = src.get() & 0xff;
            if (v != VERSION) throw new IOException("bad version");
            stage = Stage.READ_ROLE;
          }
          case READ_ROLE -> {
            role = src.get();
            if (role != ROLE_UDP && role != ROLE_TCP && role != ROLE_LOG) throw new IOException("bad role");
            stage = Stage.READ_TOKEN_LEN_HI;
          }
          case READ_TOKEN_LEN_HI -> {
            tokenLen = (src.get() & 0xff) << 8;
            stage = Stage.READ_TOKEN_LEN_LO;
          }
          case READ_TOKEN_LEN_LO -> {
            tokenLen |= src.get() & 0xff;
            if (tokenLen < 0 || tokenLen > MAX_TOKEN) throw new IOException("bad token len");
            tokenBytes = new byte[tokenLen];
            tokenRead = 0;
            stage = Stage.READ_TOKEN;
          }
          case READ_TOKEN -> {
            int canRead = Math.min(tokenLen - tokenRead, src.remaining());
            src.get(tokenBytes, tokenRead, canRead);
            tokenRead += canRead;
            if (tokenRead < tokenLen) return null;
            stage = role == ROLE_UDP ? Stage.READ_CHANNEL_ID : Stage.READ_OPTIONS;
            hs = new Handshake(role, role == ROLE_UDP ? 0 : -1, new String(tokenBytes, StandardCharsets.UTF_8));
          }
          case READ_CHANNEL_ID -> {
            channelId = src.get() & 0xff;
            hs = new Handshake(role, channelId, new String(tokenBytes, StandardCharsets.UTF_8));
            stage = Stage.READ_OPTIONS;
          }
          case READ_OPTIONS -> {
            if (optsLen < 0) {
              if (src.remaining() < 2) return null;
              src.mark();
              int lenHi = src.get() & 0xff;
              int lenLo = src.get() & 0xff;
              optsLen = (lenHi << 8) | lenLo;
              if (optsLen <= 0 || optsLen > MAX_OPTS) {
                src.reset();
                return done();
              }
              optsBytes = new byte[optsLen];
              optsRead = 0;
            }
            int canRead = Math.min(optsLen - optsRead, src.remaining());
            src.get(optsBytes, optsRead, canRead);
            optsRead += canRead;
            if (optsRead < optsLen) {
              return null;
            }
            Optional<ClientOptions> parsed = ClientOptions.parse(new String(optsBytes, StandardCharsets.UTF_8));
            if (parsed.isEmpty()) {
              src.reset();
              return done();
            }
            opts = parsed;
            return done();
          }
          case DONE -> {
            return result;
          }
        }
      }
      return null;
    }

    HandshakeResult done() {
      if (result != null) return result;
      stage = Stage.DONE;
      result = new HandshakeResult(hs, opts);
      return result;
    }
  }

  static final class TcpConnectParser {
    private static final int READ_ADDR_TYPE = 0;
    private static final int READ_ADDR = 1;
    private static final int READ_PORT = 2;

    private int stage = READ_ADDR_TYPE;
    private byte addrType;
    private int ipLen;
    private int ipRead;
    private byte[] ip;
    private int port;

    TcpConnect read(ByteBuffer src) throws IOException {
      while (src.hasRemaining()) {
        if (stage == READ_ADDR_TYPE) {
          addrType = src.get();
          if (addrType != ADDR_V4 && addrType != ADDR_V6) throw new IOException("bad addr type");
          ipLen = addrType == ADDR_V6 ? 16 : 4;
          ip = new byte[ipLen];
          ipRead = 0;
          stage = READ_ADDR;
        }
        if (stage == READ_ADDR) {
          int canRead = Math.min(ipLen - ipRead, src.remaining());
          src.get(ip, ipRead, canRead);
          ipRead += canRead;
          if (ipRead < ipLen) return null;
          stage = READ_PORT;
        }
        if (stage == READ_PORT) {
          if (src.remaining() < 2) return null;
          port = ((src.get() & 0xff) << 8) | (src.get() & 0xff);
          return new TcpConnect(addrType, InetAddress.getByAddress(ip), port);
        }
      }
      return null;
    }
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
