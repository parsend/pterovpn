package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

final class UpdateRunner implements Runnable {
  private static final String USER_AGENT = "PteraVPN-Update/1.0";
  private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern JAR_URL_PATTERN = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"(https://[^\"]+\\.jar)\"");
  private static final byte[] ZIP_MAGIC = {0x50, 0x4b, 0x03, 0x04};

  private final Path jarPath;
  private final Config cfg;
  private final String currentVersion;
  private final Logger log;

  UpdateRunner(Path jarPath, Config cfg) {
    this.jarPath = jarPath;
    this.cfg = cfg;
    this.currentVersion = versionFromManifest();
    this.log = Log.logger(UpdateRunner.class);
  }

  @Override
  public void run() {
    if (!Files.isRegularFile(jarPath)) {
      log.info("Update check disabled: not running from a JAR file");
      return;
    }
    if (currentVersion == null || currentVersion.isEmpty()) {
      log.warning("Update check disabled: no Implementation-Version in manifest");
      return;
    }
    if (cfg.updateRepo() == null || cfg.updateRepo().isBlank()) {
      log.warning("Update check disabled: update.repo not set");
      return;
    }
    long intervalMs = cfg.updateCheckIntervalMinutes() * 60L * 1000;
    log.info("Update runner started, current version " + currentVersion + ", check every " + cfg.updateCheckIntervalMinutes() + " min");
    while (true) {
      try {
        Thread.sleep(intervalMs);
        checkAndApply();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.info("Update runner stopped");
        return;
      } catch (Exception e) {
        log.warning("Update check failed: " + e.getMessage());
      }
    }
  }

  private void checkAndApply() throws IOException, InterruptedException {
    String repo = cfg.updateRepo().trim();
    if (repo.contains(" ")) {
      log.warning("Invalid update.repo (no spaces): " + repo);
      return;
    }
    String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", USER_AGENT)
        .GET()
        .build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      log.warning("GitHub API returned " + resp.statusCode());
      return;
    }
    String body = resp.body();
    String latestTag = findFirst(TAG_PATTERN, body);
    String jarUrl = findFirst(JAR_URL_PATTERN, body);
    if (latestTag == null || jarUrl == null) {
      log.warning("Could not parse release (tag or jar url missing)");
      return;
    }
    String normalizedLatest = normalizeVersion(latestTag);
    String normalizedCurrent = normalizeVersion(currentVersion);
    if (compareVersions(normalizedLatest, normalizedCurrent) <= 0) {
      return;
    }
    log.info("New version " + latestTag + " available, downloading");
    Path dir = jarPath.getParent();
    Path tmp = dir.resolve("server.jar.new");
    downloadTo(jarUrl, tmp);
    if (!isZip(tmp)) {
      Files.deleteIfExists(tmp);
      log.warning("Downloaded file is not a valid JAR, skipped");
      return;
    }
    Path backup = dir.resolve("server.jar.bak");
    Files.deleteIfExists(backup);
    if (Files.exists(jarPath)) {
      Files.move(jarPath, backup, StandardCopyOption.REPLACE_EXISTING);
    }
    Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING);
    log.info("Updated to " + latestTag + ", restarting (exit " + cfg.updateRestartExitCode() + ")");
    System.exit(cfg.updateRestartExitCode());
  }

  private static String findFirst(Pattern p, String s) {
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }

  private static String versionFromManifest() {
    String v = Main.class.getPackage().getImplementationVersion();
    return v != null ? v.trim() : "";
  }

  private static String normalizeVersion(String tag) {
    String s = tag.trim();
    if (s.startsWith("v")) s = s.substring(1);
    return s;
  }

  private static int compareVersions(String a, String b) {
    String[] aa = a.split("\\.");
    String[] bb = b.split("\\.");
    for (int i = 0; i < Math.max(aa.length, bb.length); i++) {
      int na = i < aa.length ? parseSegment(aa[i]) : 0;
      int nb = i < bb.length ? parseSegment(bb[i]) : 0;
      if (na != nb) return Integer.compare(na, nb);
    }
    return 0;
  }

  private static int parseSegment(String s) {
    s = s.trim();
    int i = 0;
    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
    if (i == 0) return 0;
    try {
      return Integer.parseInt(s.substring(0, i));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private void downloadTo(String url, Path target) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .GET()
        .build();
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() != 200) {
      throw new IOException("Download returned " + resp.statusCode());
    }
    try (InputStream in = resp.body()) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static boolean isZip(Path p) throws IOException {
    byte[] head = new byte[4];
    try (InputStream in = Files.newInputStream(p)) {
      int n = in.read(head);
      if (n != 4) return false;
    }
    for (int i = 0; i < 4; i++) {
      if (head[i] != ZIP_MAGIC[i]) return false;
    }
    return true;
  }
}
