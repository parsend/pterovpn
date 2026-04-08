package dev.c0redev.pteravpn;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class XorStream {

    XorStream(byte[] key) {
    }

    InputStream wrapInput(InputStream in) {
        return new FilterInputStream(in) {};
    }

    OutputStream wrapOutput(OutputStream out) {
        return new FilterOutputStream(out) {};
    }

    void decode(byte[] b, int off, int len) {
        return;
    }

    void encode(byte[] b, int off, int len) {
        return;
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
