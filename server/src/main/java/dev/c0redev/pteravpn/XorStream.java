package dev.c0redev.pteravpn;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class XorStream {
  private final byte[] key;
  private int rPos;
  private int wPos;

  XorStream(byte[] key) {
    this.key = key;
  }

  InputStream wrapInput(InputStream in) {
    return new FilterInputStream(in) {
      @Override
      public int read() throws IOException {
        int b = in.read();
        if (b == -1) return -1;
        int v = (b ^ key[rPos % key.length]) & 0xff;
        rPos++;
        return v;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n <= 0) return n;
        decode(b, off, n);
        return n;
      }
    };
  }

  OutputStream wrapOutput(OutputStream out) {
    return new FilterOutputStream(out) {
      @Override
      public void write(int b) throws IOException {
        out.write((b ^ (key[wPos % key.length] & 0xff)) & 0xff);
        wPos++;
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        int start = wPos;
        encode(b, off, len);
        out.write(b, off, len);
        restoreWrite(b, off, len, start);
      }
    };
  }

  void decode(byte[] b, int off, int len) {
    for (int i = 0; i < len; i++) {
      b[off + i] ^= key[rPos % key.length];
      rPos++;
    }
  }

  void encode(byte[] b, int off, int len) {
    for (int i = 0; i < len; i++) {
      b[off + i] ^= key[(wPos + i) % key.length];
    }
    wPos += len;
  }

  void restoreWrite(byte[] b, int off, int len, int start) {
    for (int i = 0; i < len; i++) {
      b[off + i] ^= key[(start + i) % key.length];
    }
  }

  static byte[] keyFromToken(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] h = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] key = new byte[32];
      System.arraycopy(h, 0, key, 0, 32);
      return key;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }
}
