package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Protocol {
  static final byte VERSION = 1;
  static final byte ROLE_UDP = 1;
  static final byte ROLE_TCP = 2;
  static final byte TYPE_HANDSHAKE = 0;
  static final byte TYPE_TCP_CONNECT = 1;
  static final byte TYPE_UDP_FRAME = 2;
  static final byte ADDR_V4 = 4;
  static final byte ADDR_V6 = 6;
  static final int MAGIC_LEN = 5;
  static final int MAX_TOKEN = 4096;
  static final int MAX_FRAME = 64 * 1024 + 64;

  static byte[] magicFromToken(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update("ptevpn:".getBytes(StandardCharsets.UTF_8));
      md.update(token.getBytes(StandardCharsets.UTF_8));
      byte[] h = md.digest();
      byte[] out = new byte[MAGIC_LEN];
      System.arraycopy(h, 0, out, 0, MAGIC_LEN);
      return out;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  static Handshake readMessage(InputStream in, byte[] expectedMagic) throws IOException {
    byte[] got = readN(in, MAGIC_LEN);
    for (int i = 0; i < MAGIC_LEN; i++) {
      if (got[i] != expectedMagic[i]) throw new IOException("bad magic");
    }
    int t = readU8(in);
    if (t != TYPE_HANDSHAKE) throw new IOException("expected handshake");
    return readHandshakeBody(in);
  }

  static Object readMessageAfterHandshake(InputStream in) throws IOException {
    int t = readU8(in);
    return switch (t) {
      case TYPE_TCP_CONNECT -> readTcpConnectBody(in);
      case TYPE_UDP_FRAME -> readUdpFrameBody(in);
      default -> throw new IOException("unknown msg type");
    };
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

  static TcpConnect readTcpConnectBody(InputStream in) throws IOException {
    int at = readU8(in);
    InetAddress ip = readAddr(in, (byte) at);
    int port = readU16(in);
    return new TcpConnect((byte) at, ip, port);
  }

  static UdpFrame readUdpFrameBody(InputStream in) throws IOException {
    int frameLen = readU32(in);
    if (frameLen < 1 || frameLen > MAX_FRAME) throw new IOException("bad frame len");
    byte[] buf = readN(in, frameLen);
    byte at = buf[0];
    int off = 1;
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
    byte[] payload = new byte[buf.length - off];
    System.arraycopy(buf, off, payload, 0, payload.length);
    return new UdpFrame(at, srcPort, dst, dstPort, payload);
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
}
