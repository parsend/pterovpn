package dev.c0redev.pteravpn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class MtlsMaterial {
  private static final String KEY_PASSWORD = "changeit";

  private final byte[] serverCertPem;
  private final byte[] serverKeyPem;
  private final byte[] clientCertPem;
  private final byte[] clientKeyPem;
  private final SSLContext serverContext;

  private MtlsMaterial(byte[] serverCertPem, byte[] serverKeyPem, byte[] clientCertPem, byte[] clientKeyPem,
      SSLContext serverContext) {
    this.serverCertPem = serverCertPem;
    this.serverKeyPem = serverKeyPem;
    this.clientCertPem = clientCertPem;
    this.clientKeyPem = clientKeyPem;
    this.serverContext = serverContext;
  }

  byte[] serverCertPem() { return serverCertPem; }
  byte[] clientCertPem() { return clientCertPem; }
  byte[] clientKeyPem() { return clientKeyPem; }
  SSLContext serverContext() { return serverContext; }

  static MtlsMaterial loadOrCreate(Path baseDir) throws IOException {
    Path dir = baseDir.resolve("mtls");
    Path serverCertPath = dir.resolve("server-cert.pem");
    Path serverKeyPath = dir.resolve("server-key.pem");
    Path clientCertPath = dir.resolve("client-cert.pem");
    Path clientKeyPath = dir.resolve("client-key.pem");
    try {
      if (Files.isRegularFile(serverCertPath) && Files.isRegularFile(serverKeyPath)
          && Files.isRegularFile(clientCertPath) && Files.isRegularFile(clientKeyPath)) {
        byte[] serverCertPem = Files.readAllBytes(serverCertPath);
        byte[] serverKeyPem = Files.readAllBytes(serverKeyPath);
        byte[] clientCertPem = Files.readAllBytes(clientCertPath);
        byte[] clientKeyPem = Files.readAllBytes(clientKeyPath);
        return new MtlsMaterial(serverCertPem, serverKeyPem, clientCertPem, clientKeyPem,
            buildServerContext(serverCertPem, serverKeyPem, clientCertPem));
      }
      Files.createDirectories(dir);
      Identity server = generateIdentity("pteravpn-server", true);
      Identity client = generateIdentity("pteravpn-client", false);
      byte[] serverCertPem = pem("CERTIFICATE", server.cert().getEncoded());
      byte[] serverKeyPem = pem("PRIVATE KEY", server.key().getEncoded());
      byte[] clientCertPem = pem("CERTIFICATE", client.cert().getEncoded());
      byte[] clientKeyPem = pem("PRIVATE KEY", client.key().getEncoded());
      Files.write(serverCertPath, serverCertPem);
      Files.write(serverKeyPath, serverKeyPem);
      Files.write(clientCertPath, clientCertPem);
      Files.write(clientKeyPath, clientKeyPem);
      return new MtlsMaterial(serverCertPem, serverKeyPem, clientCertPem, clientKeyPem,
          buildServerContext(serverCertPem, serverKeyPem, clientCertPem));
    } catch (GeneralSecurityException e) {
      throw new IOException("mtls material init failed", e);
    }
  }

  private static SSLContext buildServerContext(byte[] serverCertPem, byte[] serverKeyPem, byte[] clientCertPem)
      throws GeneralSecurityException, IOException {
    X509Certificate serverCert = loadCert(serverCertPem);
    PrivateKey serverKey = loadKey(serverKeyPem);
    X509Certificate clientCert = loadCert(clientCertPem);

    KeyStore keyStore = KeyStore.getInstance("JKS");
    keyStore.load(null, null);
    keyStore.setKeyEntry("server", serverKey, KEY_PASSWORD.toCharArray(),
        new java.security.cert.Certificate[]{serverCert});

    KeyStore trustStore = KeyStore.getInstance("JKS");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("client", clientCert);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, KEY_PASSWORD.toCharArray());

    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trustStore);

    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    return ctx;
  }

  private static X509Certificate loadCert(byte[] pem) throws GeneralSecurityException {
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem));
  }

  private static PrivateKey loadKey(byte[] pem) throws GeneralSecurityException {
    byte[] der = decodePem(pem, "PRIVATE KEY");
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private static byte[] pem(String kind, byte[] der) {
    String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
    String text = "-----BEGIN " + kind + "-----\n" + b64 + "\n-----END " + kind + "-----\n";
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] decodePem(byte[] pem, String kind) {
    String s = new String(pem, StandardCharsets.UTF_8)
        .replace("-----BEGIN " + kind + "-----", "")
        .replace("-----END " + kind + "-----", "")
        .replace("\r", "")
        .replace("\n", "")
        .trim();
    return Base64.getDecoder().decode(s);
  }

  private static Identity generateIdentity(String commonName, boolean server) throws GeneralSecurityException, IOException {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048, new SecureRandom());
    KeyPair pair = kpg.generateKeyPair();

    Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
    Date notAfter = new Date(System.currentTimeMillis() + 3650L * 24L * 60L * 60L * 1000L);
    X500Name name = new X500Name("CN=" + commonName);
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
        name,
        new BigInteger(160, new SecureRandom()),
        notBefore,
        notAfter,
        name,
        pair.getPublic()
    );
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    builder.addExtension(
        Extension.extendedKeyUsage,
        false,
        new ExtendedKeyUsage(server ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth)
    );
    try {
      ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
      X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
      cert.checkValidity(new Date());
      cert.verify(pair.getPublic());
      return new Identity(pair.getPrivate(), cert);
    } catch (OperatorCreationException e) {
      throw new GeneralSecurityException("mtls signer init failed", e);
    }
  }

  private record Identity(PrivateKey key, X509Certificate cert) {}
}
