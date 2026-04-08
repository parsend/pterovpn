package dev.c0redev.pteravpn;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class XorStream {
    private final byte[] key;
    private int rPos;
    private int wPos;

    XorStream(byte[] key) {
        this.key = key == null ? new byte[0] : key.clone();
    }

    InputStream wrapInput(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws java.io.IOException {
                int b = super.read();
                if (b < 0) return b;
                byte[] one = {(byte) b};
                decode(one, 0, 1);
                return one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                int n = super.read(b, off, len);
                if (n > 0) decode(b, off, n);
                return n;
            }
        };
    }

    OutputStream wrapOutput(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(int b) throws java.io.IOException {
                byte[] one = {(byte) b};
                encode(one, 0, 1);
                super.write(one[0] & 0xff);
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                if (len <= 0) return;
                byte[] tmp = new byte[len];
                System.arraycopy(b, off, tmp, 0, len);
                encode(tmp, 0, len);
                out.write(tmp, 0, len);
            }
        };
    }

    void decode(byte[] b, int off, int len) {
        xor(b, off, len, true);
    }

    void encode(byte[] b, int off, int len) {
        xor(b, off, len, false);
    }

    private void xor(byte[] b, int off, int len, boolean readPath) {
        if (b == null || len <= 0 || key.length == 0) return;
        int pos = readPath ? rPos : wPos;
        int kl = key.length;
        for (int i = 0; i < len; i++) {
            b[off + i] ^= key[pos % kl];
            pos++;
        }
        if (readPath) {
            rPos = pos;
        } else {
            wPos = pos;
        }
    }

    static byte[] keyFromToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            byte[] key = new byte[32];
            System.arraycopy(h, 0, key, 0, 32);
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
