package dev.c0redev.pteravpn;

import io.netty.util.NetUtil;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.asn1.x500.X500Name;

import java.io.File;
import java.io.Writer;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.logging.Logger;


final class QuicEphemeralCert {
  private static final Logger log = Log.logger(QuicEphemeralCert.class);

  private QuicEphemeralCert() {}

  static final class Material {
    final PrivateKey privateKey;
    final X509Certificate certificate;

    Material(PrivateKey privateKey, X509Certificate certificate) {
      this.privateKey = privateKey;
      this.certificate = certificate;
    }
  }

  static Material generate(String identity) throws Exception {
    ensureBc();
    String cn = (identity != null && !identity.isBlank()) ? identity.trim() : "pteravpn-quic";

    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair kp = kpg.generateKeyPair();

    X500Name subject = new X500Name("CN=" + cn);
    BigInteger serial = new BigInteger(128, new SecureRandom());
    Date notBefore = new Date();
    Date notAfter = new Date(notBefore.getTime() + 365L * 86400_000L);

    JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
        subject, serial, notBefore, notAfter, subject, kp.getPublic());

    if (NetUtil.isValidIpV4Address(cn) || NetUtil.isValidIpV6Address(cn)) {
      InetAddress addr = InetAddress.getByName(cn);
      certBuilder.addExtension(Extension.subjectAlternativeName, false,
          new GeneralNames(new GeneralName(GeneralName.iPAddress, new DEROctetString(addr.getAddress()))));
    } else {
      certBuilder.addExtension(Extension.subjectAlternativeName, false,
          new GeneralNames(new GeneralName(GeneralName.dNSName, cn)));
    }

    ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .build(kp.getPrivate());
    X509CertificateHolder holder = certBuilder.build(signer);
    X509Certificate cert = new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(holder);
    return new Material(kp.getPrivate(), cert);
  }

  
  static boolean tryPersist(Material m, File certFile, File keyFile) {
    try {
      var cp = certFile.toPath();
      var kp = keyFile.toPath();
      if (cp.getParent() != null) {
        Files.createDirectories(cp.getParent());
      }
      try (Writer w = Files.newBufferedWriter(cp, StandardCharsets.US_ASCII);
           JcaPEMWriter pw = new JcaPEMWriter(w)) {
        pw.writeObject(m.certificate);
      }
      try (Writer w = Files.newBufferedWriter(kp, StandardCharsets.US_ASCII);
           JcaPEMWriter pw = new JcaPEMWriter(w)) {
        pw.writeObject(m.privateKey);
      }
      return true;
    } catch (Exception e) {
      log.warning("QUIC could not persist ephemeral cert: " + e.getMessage());
      return false;
    }
  }

  private static void ensureBc() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }
}
