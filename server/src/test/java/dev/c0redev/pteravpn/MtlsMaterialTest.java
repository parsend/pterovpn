package dev.c0redev.pteravpn;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MtlsMaterialTest {

  @Test
  void loadOrCreateWritesMaterial() throws Exception {
    Path dir = Files.createTempDirectory("pteravpn-mtls-test");
    MtlsMaterial material = MtlsMaterial.loadOrCreate(dir);
    assertNotNull(material.serverContext());
    assertTrue(material.serverCertPem().length > 0);
    assertTrue(material.clientCertPem().length > 0);
    assertTrue(material.clientKeyPem().length > 0);
    assertTrue(Files.isRegularFile(dir.resolve("mtls/server-cert.pem")));
    assertTrue(Files.isRegularFile(dir.resolve("mtls/server-key.pem")));
    assertTrue(Files.isRegularFile(dir.resolve("mtls/client-cert.pem")));
    assertTrue(Files.isRegularFile(dir.resolve("mtls/client-key.pem")));
  }
}
