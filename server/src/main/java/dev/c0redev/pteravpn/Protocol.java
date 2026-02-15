package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class Protocol {
  static final byte[] MAGIC = new byte[] { 'P','T','V','P','N' };
  static final byte VERSION = 1;

  static final byte ROLE_UDP = 1;
  static final byte ROLE_TCP = 2;
  static final byte ROLE_QUIC = 3;

  static final byte MSG_UDP = 1;

  static final byte ADDR_V4 = 4;
  static final byte ADDR_V6 = 6;

  static void writeHandshake(OutputStream out, byte role, byte channelId, String token) throws IOException {
    out.write(MAGIC);
    out.write(VERSION);
    out.write(role);
    byte[] tok = token.getBytes(StandardCharsets.UTF_8);
    writeU16(out, tok.length);
    out.write(tok);
    if (role == ROLE_UDP || role == ROLE_QUIC) out.write(channelId & 0xff);
    out.flush();
  }

  static Handshake readHandshake(InputStream in) throws IOException {
    byte[] magic = readN(in, MAGIC.length);
    for (int i = 0; i < MAGIC.length; i++) {
      if (magic[i] != MAGIC[i]) throw new IOException("bad magic");
    }
    int ver = readU8(in);
    if (ver != VERSION) throw new IOException("bad version: " + ver);
    byte role = (byte) readU8(in);
    int tokenLen = readU16(in);
    if (tokenLen < 0 || tokenLen > 4096) throw new IOException("bad token len");
    String token = new String(readN(in, tokenLen), StandardCharsets.UTF_8);
    int channelId = -1;
    if (role == ROLE_UDP || role == ROLE_QUIC) channelId = readU8(in);
    return new Handshake(role, channelId, token);
  }

  static TcpConnect readTcpConnect(InputStream in) throws IOException {
    int at = readU8(in);
    InetAddress ip = readAddr(in, (byte) at);
    int port = readU16(in);
    return new TcpConnect((byte) at, ip, port);
  }

  static void writeTcpConnect(OutputStream out, byte addrType, InetAddress ip, int port) throws IOException {
    out.write(addrType & 0xff);
    out.write(ip.getAddress());
    writeU16(out, port);
    out.flush();
  }

  static UdpFrame readUdpFrame(InputStream in) throws IOException {
    int frameLen = readU32(in);
    if (frameLen < 1 || frameLen > (64 * 1024 + 64)) throw new IOException("bad frame len");
    byte[] buf = readN(in, frameLen);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
    byte msg = bb.get();
    if (msg != MSG_UDP) throw new IOException("bad msg: " + msg);
    byte at = bb.get();
    int srcPort = bb.getShort() & 0xffff;
    InetAddress dst = readAddr(bb, at);
    int dstPort = bb.getShort() & 0xffff;
    byte[] payload = new byte[bb.remaining()];
    bb.get(payload);
    return new UdpFrame(at, srcPort, dst, dstPort, payload);
  }

  static void writeUdpFrame(OutputStream out, UdpFrame f) throws IOException {
    byte[] ip = f.dst().getAddress();
    int ipLen = (f.addrType() == ADDR_V6) ? 16 : 4;
    if (ip.length != ipLen) throw new IOException("ip mismatch");
    int frameLen = 1 + 1 + 2 + ipLen + 2 + f.payload().length;
    writeU32(out, frameLen);
    out.write(MSG_UDP);
    out.write(f.addrType() & 0xff);
    writeU16(out, f.srcPort());
    out.write(ip);
    writeU16(out, f.dstPort());
    out.write(f.payload());
  }

  static InetAddress readAddr(InputStream in, byte addrType) throws IOException {
    return switch (addrType) {
      case ADDR_V4 -> InetAddress.getByAddress(readN(in, 4));
      case ADDR_V6 -> InetAddress.getByAddress(readN(in, 16));
      default -> throw new IOException("bad addr type: " + addrType);
    };
  }

  static InetAddress readAddr(ByteBuffer bb, byte addrType) throws IOException {
    try {
      return switch (addrType) {
        case ADDR_V4 -> InetAddress.getByAddress(readN(bb, 4));
        case ADDR_V6 -> InetAddress.getByAddress(readN(bb, 16));
        default -> throw new IOException("bad addr type: " + addrType);
      };
    } catch (IllegalArgumentException e) {
      throw new IOException("bad addr bytes", e);
    }
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

  static byte[] readN(ByteBuffer bb, int n) throws IOException {
    if (bb.remaining() < n) throw new IOException("short frame");
    byte[] out = new byte[n];
    bb.get(out);
    return out;
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
}

