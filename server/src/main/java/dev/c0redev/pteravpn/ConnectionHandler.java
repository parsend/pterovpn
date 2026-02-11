package dev.c0redev.pteravpn;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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

      if (cfg.obfuscate()) {
        byte[] magic = new byte[Protocol.MAGIC.length];
        int n = 0;
        while (n < magic.length) {
          int r = in.read(magic, n, magic.length - n);
          if (r <= 0) throw new IOException("short read magic");
          n += r;
        }
        for (int i = 0; i < Protocol.MAGIC.length; i++) {
          if (magic[i] != Protocol.MAGIC[i]) throw new IOException("bad magic");
        }
        XorStream xor = new XorStream(XorStream.keyFromToken(cfg.token()));
        in = new BufferedInputStream(xor.wrapInput(in));
        out = xor.wrapOutput(out);
      }

      Protocol.Handshake hs = cfg.obfuscate()
          ? Protocol.readHandshakeBody(in)
          : Protocol.readHandshake(in);
      if (!cfg.token().equals(hs.token())) throw new IOException("bad token");
      if (hs.compression()) {
        in = new BufferedInputStream(new GZIPInputStream(in));
        out = new GZIPOutputStream(out);
      }
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
      if (f.msgType() == Protocol.MSG_PING) {
        Protocol.writeUdpFrame(out, new Protocol.UdpFrame(Protocol.MSG_PONG, (byte) 0, 0, null, 0, new byte[0]));
        continue;
      }
      if (f.msgType() == Protocol.MSG_PONG) continue;
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

