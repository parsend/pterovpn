package dev.c0redev.pteravpn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class Launcher {
  private static Logger log;

  public static void main(String[] args) throws Exception {
    Path jarPath = Main.jarPath();
    if (jarPath == null) {
      System.err.println("Cannot determine server.jar path");
      return;
    }

    Path base = jarPath.getParent();
    if (base == null) {
      base = Path.of(".").toAbsolutePath().normalize();
    }

    Path cfgPath = base.resolve("config.properties");
    Config cfg;
    try {
      cfg = Config.load(cfgPath);
    } catch (IOException e) {
      System.err.println("Failed to load config: " + e.getMessage());
      return;
    }

    Log.setDebug(cfg.debug());
    log = Log.logger(Launcher.class);
    log.info("Launcher base: " + base);

    int restartCode = cfg.updateRestartExitCode();
    if (restartCode == 0) {
      restartCode = 1;
    }

    while (true) {
      int code = runChild(jarPath);
      if (code == 0) {
        log.info("Server exited with code 0, stopping launcher");
        return;
      }
      if (code != restartCode) {
        log.info("Server exited with code " + code + ", restarting in 5 seconds");
        TimeUnit.SECONDS.sleep(5);
      } else {
        log.info("Server requested restart with code " + code);
      }
    }
  }

  private static int runChild(Path jarPath) {
    ProcessBuilder pb = new ProcessBuilder(
        "java",
        "-cp",
        jarPath.toString(),
        "dev.c0redev.pteravpn.Main"
    );
    pb.inheritIO();
    try {
      Process p = pb.start();
      return p.waitFor();
    } catch (IOException e) {
      log.warning("Failed to start child JVM: " + e.getMessage());
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.info("Launcher interrupted, stopping");
      return 0;
    }
  }
}

