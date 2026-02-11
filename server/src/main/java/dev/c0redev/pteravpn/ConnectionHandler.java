package dev.c0redev.pteravpn;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Logger;

final class ConnectionHandler implements Runnable {
  private static final Logger log = Log.logger(ConnectionHandler.class);

  private final Socket sock;
  private final Config cfg;
  private final UdpSessions udp;

  ConnectionHandler(Socket sock, Config cfg, UdpSessions udp) {
    this.sock = sock;
    this.cfg = cfg;
    this.udp = udp;
  }

  @Override
  public void run() {
    try (Socket s = sock) {
      InputStream in = new BufferedInputStream(s.getInputStream());
      OutputStream out = s.getOutputStream();

      Protocol.Handshake hs = Protocol.readHandshake(in);
      if (!cfg.token().equals(hs.token())) throw new IOException("bad token");
      log.info("Accepted role=" + hs.role() + " from " + s.getRemoteSocketAddress());

      if (hs.role() == Protocol.ROLE_UDP) {
        handleUdp(hs.channelId(), in, out);
        return;
      }
      if (hs.role() == Protocol.ROLE_TCP) {
        handleTcp(in, out);
        return;
      }
      throw new IOException("bad role");
    } catch (EOFException ignored) {
    } catch (IOException e) {
      log.fine("conn closed: " + e.getMessage());
    }
  }

  private void handleUdp(int channelId, InputStream in, OutputStream out) throws IOException {
    if (channelId < 0 || channelId >= cfg.udpChannels()) throw new IOException("bad udp channel");
    log.info("UDP channel " + channelId + " registered from " + sock.getRemoteSocketAddress());
    udp.setWriter(channelId, out);
    while (true) {
      Protocol.UdpFrame f = Protocol.readUdpFrame(in);
      udp.onFrame(channelId, f);
    }
  }

  private void handleTcp(InputStream in, OutputStream out) throws IOException {
    Protocol.TcpConnect c = Protocol.readTcpConnect(in);
    log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
    try (Socket remote = new Socket()) {
      remote.setTcpNoDelay(true);
      remote.setKeepAlive(true);
      remote.connect(new InetSocketAddress(c.ip(), c.port()), 10_000);

      InputStream rin = remote.getInputStream();
      OutputStream rout = remote.getOutputStream();
      OutputStream cout = out;

      Thread t1 = new Thread(() -> pump(in, rout), "tcp-up");
      Thread t2 = new Thread(() -> pump(rin, cout), "tcp-down");
      t1.setDaemon(true);
      t2.setDaemon(true);
      t1.start();
      t2.start();
      try { t1.join(); } catch (InterruptedException ignored) {}
      try { t2.join(); } catch (InterruptedException ignored) {}
    }
  }

  private static void pump(InputStream in, OutputStream out) {
    byte[] buf = new byte[32 * 1024];
    try {
      int r;
      while ((r = in.read(buf)) != -1) {
        out.write(buf, 0, r);
      }
      out.flush();
    } catch (IOException ignored) {
      log.fine("pump closed: " + ignored.getMessage());
    }
  }
}

