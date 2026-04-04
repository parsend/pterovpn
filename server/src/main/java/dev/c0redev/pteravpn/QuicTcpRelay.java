package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * tcp через quic stream: одна пара потоков на upstream
 */
final class QuicTcpRelay {
  private static final Logger log = Log.logger(QuicTcpRelay.class);
  private static final int BUF = 32 * 1024;

  private QuicTcpRelay() {}

  static void run(
      Protocol.TcpConnect c,
      InputStream quicIn,
      OutputStream quicOut,
      int connectTimeoutMs
  ) throws IOException {
    try (Socket remote = new Socket()) {
      remote.connect(new InetSocketAddress(c.ip(), c.port()), connectTimeoutMs);
      remote.setTcpNoDelay(true);
      var remoteIn = remote.getInputStream();
      var remoteOut = remote.getOutputStream();
      Thread up = new Thread(() -> copy(quicIn, remoteOut), "quic-pair-up");
      Thread down = new Thread(() -> copy(remoteIn, quicOut), "quic-pair-down");
      up.start();
      down.start();
      try {
        up.join();
        down.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        up.interrupt();
        down.interrupt();
        throw new IOException(e);
      }
    }
  }

  private static void copy(InputStream in, OutputStream out) {
    byte[] buf = new byte[BUF];
    try {
      while (true) {
        int n = in.read(buf);
        if (n < 0) {
          return;
        }
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException e) {
      log.fine("quic relay copy end: " + e.getMessage());
    }
  }
}
