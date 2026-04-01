package dev.c0redev.pteravpn;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

final class QuicTlsUtil {
  private QuicTlsUtil() {}

  static X509Certificate firstCertFromPem(File certFile) throws Exception {
    try (InputStream in = Files.newInputStream(certFile.toPath())) {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      Collection<? extends Certificate> certs = cf.generateCertificates(in);
      if (certs.isEmpty()) {
        throw new IOException("empty PEM: " + certFile);
      }
      return (X509Certificate) certs.iterator().next();
    }
  }

  static String sha256HexDer(byte[] der) throws Exception {
    byte[] d = sha256Raw(der);
    StringBuilder sb = new StringBuilder(d.length * 2);
    for (byte b : d) {
      sb.append(String.format("%02x", b & 0xff));
    }
    return sb.toString();
  }

  static byte[] sha256Raw(byte[] der) throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA-256").digest(der);
  }
}
