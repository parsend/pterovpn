package dev.c0redev.pteravpn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

  static byte[] u16(int v) {
    return new byte[]{(byte) (v >>> 8), (byte) (v & 0xff)};
  }

  static byte[] u32(int v) {
    return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) (v & 0xff)};
  }

  @Test
  void readHandshakeTcp() throws IOException {
    byte[] tok = "secret".getBytes();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write("PTVPN".getBytes());
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_TCP);
    buf.write(u16(tok.length));
    buf.write(tok);
    var hr = Protocol.readHandshake(new ByteArrayInputStream(buf.toByteArray()));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_TCP, hs.role());
    assertEquals("secret", hs.token());
    assertEquals(-1, hs.channelId());
  }

  @Test
  void readHandshakeUdp() throws IOException {
    byte[] tok = "x".getBytes();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write("PTVPN".getBytes());
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_UDP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(3);
    var hr = Protocol.readHandshake(new ByteArrayInputStream(buf.toByteArray()));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_UDP, hs.role());
    assertEquals(3, hs.channelId());
  }

  @Test
  void readHandshakeBadMagic() {
    var in = new ByteArrayInputStream("XXXXX".getBytes());
    assertThrows(IOException.class, () -> Protocol.readHandshake(in));
  }

  @Test
  void readTcpConnect() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.ADDR_V4);
    buf.write(new byte[]{1, 2, 3, 4});
    buf.write(u16(443));
    var c = Protocol.readTcpConnect(new ByteArrayInputStream(buf.toByteArray()));
    assertArrayEquals(InetAddress.getByAddress(new byte[]{1, 2, 3, 4}).getAddress(), c.ip().getAddress());
    assertEquals(443, c.port());
  }

  @Test
  void udpFrameRoundtrip() throws IOException {
    var f = new Protocol.UdpFrame(Protocol.ADDR_V4, 12345,
        InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53,
        new byte[]{1, 2, 3, 4, 5});
    var out = new ByteArrayOutputStream();
    Protocol.writeUdpFrame(out, f);
    var got = Protocol.readUdpFrame(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(f.srcPort(), got.srcPort());
    assertEquals(f.dstPort(), got.dstPort());
    assertArrayEquals(f.dst().getAddress(), got.dst().getAddress());
    assertArrayEquals(f.payload(), got.payload());
  }

  @Test
  void skipUntilMagicWithPrefix() throws IOException {
    byte[] pad = new byte[]{0x00, 0x01, 0x02, 0x03};
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(pad);
    buf.write("PTVPN".getBytes());
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_UDP);
    buf.write(u16(1));
    buf.write('x');
    buf.write(0);
    var hr = Protocol.readHandshake(new ByteArrayInputStream(buf.toByteArray()));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_UDP, hs.role());
    assertEquals("x", hs.token());
  }

  @Test
  void clientOptionsParse() {
    var opt = Protocol.ClientOptions.parse("{\"padS4\":48}");
    assertTrue(opt.isPresent());
    assertEquals(48, opt.get().padS4());
  }

  @Test
  void clientOptionsParseEmpty() {
    var opt = Protocol.ClientOptions.parse("{}");
    assertTrue(opt.isPresent());
    assertEquals(32, opt.get().padS4());
  }

  @Test
  void udpFrameEmptyPayload() throws IOException {
    var f = new Protocol.UdpFrame(Protocol.ADDR_V4, 0,
        InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 53,
        new byte[0]);
    var out = new ByteArrayOutputStream();
    Protocol.writeUdpFrame(out, f);
    var got = Protocol.readUdpFrame(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(0, got.payload().length);
  }
}
