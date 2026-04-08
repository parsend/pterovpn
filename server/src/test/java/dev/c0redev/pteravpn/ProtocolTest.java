package dev.c0redev.pteravpn;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

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
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_TCP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(u16(0));
    var hr = Protocol.readHandshake(new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray())));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_TCP, hs.role());
    assertEquals("secret", hs.token());
    assertEquals(-1, hs.channelId());
  }

  @Test
  void readHandshakeTcpWithOptsThenReadTcpConnect() throws IOException {
    byte[] tok = "secret".getBytes();
    String opts = "{\"padS4\":40}";
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_TCP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(u16(opts.length()));
    buf.write(opts.getBytes(StandardCharsets.UTF_8));
    buf.write(Protocol.ADDR_V4);
    buf.write(new byte[]{9, 9, 9, 9});
    buf.write(u16(443));
    var in = new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray()));
    var hr = Protocol.readHandshake(in);
    assertTrue(hr.opts().isPresent());
    assertEquals(40, hr.opts().get().padS4());
    var c = Protocol.readTcpConnect(in);
    assertArrayEquals(InetAddress.getByAddress(new byte[]{9, 9, 9, 9}).getAddress(), c.ip().getAddress());
    assertEquals(443, c.port());
  }

  @Test
  void readHandshakeUdp() throws IOException {
    byte[] tok = "x".getBytes();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_UDP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(3);
    buf.write(u16(0));
    var hr = Protocol.readHandshake(new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray())));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_UDP, hs.role());
    assertEquals(3, hs.channelId());
  }

  @Test
  void readHandshakeBadVersion() {
    var in = new ByteArrayInputStream(new byte[] {99, Protocol.ROLE_TCP, 0, 0, 0, 0});
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
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_UDP);
    buf.write(u16(1));
    buf.write('x');
    buf.write(0);
    buf.write(u16(0));
    var in = new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray()));
    assertThrows(IOException.class, () -> Protocol.readHandshake(in));
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

  @Test
  void serverHelloCapsRoundtrip() throws IOException {
    var caps = new Protocol.ServerHelloCaps(
        Protocol.CAPS_VERSION,
        1,
        Protocol.TRANSPORT_TCP | Protocol.TRANSPORT_QUIC,
        Protocol.FEAT_IPV6,
        7443,
        8443,
        2,
        new byte[]{9, 8, 7},
        null);
    var out = new ByteArrayOutputStream();
    Protocol.writeServerHelloCaps(out, caps);
    var got = Protocol.readServerHelloCaps(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(caps.version(), got.version());
    assertEquals(caps.transportMask(), got.transportMask());
    assertEquals(caps.featureBits(), got.featureBits());
    assertEquals(caps.quicPort(), got.quicPort());
    assertEquals(caps.tcpPortHint(), got.tcpPortHint());
    assertArrayEquals(caps.nonce(), got.nonce());
    assertNull(got.quicLeafPinSha256());
  }

  @Test
  void serverHelloCapsRoundtripWithQuicPin() throws IOException {
    byte[] pin = new byte[32];
    for (int i = 0; i < 32; i++) pin[i] = (byte) i;
    var caps = new Protocol.ServerHelloCaps(
        Protocol.CAPS_VERSION,
        0,
        Protocol.TRANSPORT_QUIC,
        0,
        4433,
        0,
        0,
        new byte[]{1},
        pin);
    var out = new ByteArrayOutputStream();
    Protocol.writeServerHelloCaps(out, caps);
    var got = Protocol.readServerHelloCaps(new ByteArrayInputStream(out.toByteArray()));
    assertArrayEquals(pin, got.quicLeafPinSha256());
  }
}
