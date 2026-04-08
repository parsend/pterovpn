package dev.c0redev.pteravpn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class XorStreamTest {

  @Test
  void roundtrip() throws IOException {
    var key = XorStream.keyFromToken("secret");
    var xor = new XorStream(key);
    var data = "hello vpn".getBytes();
    var plain = data.clone();
    var buf = new ByteArrayOutputStream();
    try (var w = xor.wrapOutput(buf)) {
      w.write(data);
    }
    var out = buf.toByteArray();
    assertArrayEquals(plain, out);

    var xor2 = new XorStream(key);
    var in = xor2.wrapInput(new ByteArrayInputStream(out));
    var dec = in.readAllBytes();
    assertArrayEquals(plain, dec);
  }

  @Test
  void sameTokenSameKey() {
    var a = XorStream.keyFromToken("x");
    var b = XorStream.keyFromToken("x");
    assertArrayEquals(a, b);
  }

  @Test
  void diffTokenDiffKey() {
    var a = XorStream.keyFromToken("a");
    var b = XorStream.keyFromToken("b");
    assertFalse(java.util.Arrays.equals(a, b));
  }
}
