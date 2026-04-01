package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

final class SessionHandler {
  interface TcpHandler {
    void onTcp(Protocol.TcpConnect connect, InputStream in) throws IOException;
  }

  private static final Logger log = Log.logger(SessionHandler.class);

  private final Config cfg;
  private final UdpSessions udp;
  private final String remote;
  private final Runnable onDone;

  SessionHandler(Config cfg, UdpSessions udp, String remote, Runnable onDone) {
    this.cfg = cfg;
    this.udp = udp;
    this.remote = remote;
    this.onDone = onDone;
  }

  
  void handle(
      Protocol.Handshake hs,
      Protocol.HandshakeResult hr,
      InputStream in,
      OutputStream out,
      TcpHandler tcpHandler,
      ExecutorService udpOffloadExecutor
  ) throws IOException {
    log.info("Accepted role=" + hs.role() + " from " + remote);
    if (hs.role() == Protocol.ROLE_UDP) {
      Runnable r = () -> {
        try {
          handleUdp(hs.channelId(), in, out, hr.opts());
        } catch (IOException e) {
          log.fine("udp role ended: " + e.getMessage());
        } finally {
          onDone.run();
        }
      };
      if (udpOffloadExecutor != null) {
        udpOffloadExecutor.submit(r);
        return;
      }
      r.run();
      return;
    }
    if (hs.role() == Protocol.ROLE_TCP) {
      Protocol.TcpConnect c = Protocol.readTcpConnect(in);
      tcpHandler.onTcp(c, in);
      return;
    }
    throw new IOException("bad role");
  }

  private void handleUdp(int channelId, InputStream in, OutputStream out, Optional<Protocol.ClientOptions> opts)
      throws IOException {
    UdpSessions.UdpChannelWriter writer = udp.createWriter(out, opts.orElse(null));
    try {
      if (channelId < 0 || channelId >= cfg.udpChannels()) throw new IOException("bad udp channel");
      log.info("UDP channel " + channelId + " registered from " + remote);
      while (true) {
        Protocol.UdpFrame f = Protocol.readUdpFrame(in);
        udp.onFrame(writer, channelId, f);
      }
    } finally {
      udp.removeWriter(writer);
    }
  }
}
