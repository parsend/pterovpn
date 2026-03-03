package dev.c0redev.pteravpn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

final class UdpObfuscate {

  private static final SecureRandom RND = new SecureRandom();

  static byte[] udpPacketKey(String token, byte[] nonce) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(token.getBytes(StandardCharsets.UTF_8));
      md.update(nonce);
      return md.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  static byte[] deobfuscateUdpPacket(String token, byte[] cipher) {
    if (cipher == null || cipher.length < 5) return null;
    byte[] nonce = new byte[4];
    System.arraycopy(cipher, 0, nonce, 0, 4);
    byte[] key = udpPacketKey(token, nonce);
    byte[] plain = new byte[cipher.length - 4];
    for (int i = 0; i < plain.length; i++) {
      plain[i] = (byte) (cipher[4 + i] ^ key[i % key.length]);
    }
    return plain;
  }

  static byte[] obfuscateUdpPacket(String token, byte[] plain) {
    if (plain == null || plain.length == 0) return null;
    byte[] nonce = new byte[4];
    RND.nextBytes(nonce);
    byte[] key = udpPacketKey(token, nonce);
    byte[] out = new byte[4 + plain.length];
    System.arraycopy(nonce, 0, out, 0, 4);
    for (int i = 0; i < plain.length; i++) {
      out[4 + i] = (byte) (plain[i] ^ key[i % key.length]);
    }
    return out;
  }
}
