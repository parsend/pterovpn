package dev.c0redev.pteravpn;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);

    private final Socket sock;
    private final Config cfg;
    private final UdpSessions udp;
    private final ExecutorService pumpPool;

    ConnectionHandler(Socket sock, Config cfg, UdpSessions udp, ExecutorService pumpPool) {
        this.sock = sock;
        this.cfg = cfg;
        this.udp = udp;
        this.pumpPool = pumpPool;
    }

    @Override
    public void run() {
        try (Socket s = sock) {
            var xor = new XorStream(XorStream.keyFromToken(cfg.token()));
            InputStream in = xor.wrapInput(
                new BufferedInputStream(s.getInputStream())
            );
            OutputStream out = xor.wrapOutput(s.getOutputStream());

            Protocol.HandshakeResult hr = Protocol.readHandshake(in);
            Protocol.Handshake hs = hr.handshake();
            if (
                !MessageDigest.isEqual(
                    cfg.token().getBytes(StandardCharsets.UTF_8),
                    hs.token().getBytes(StandardCharsets.UTF_8)
                )
            ) {
                throw new IOException("bad token");
            }
            log.info(
                "Accepted role=" +
                    hs.role() +
                    " from " +
                    s.getRemoteSocketAddress()
            );

            if (hs.role() == Protocol.ROLE_UDP) {
                Protocol.writeServerHello(out, cfg.udpSupport(), cfg.effectiveUdpPort());
                handleUdp(hs.channelId(), in, out, hr.opts());
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

    private void handleUdp(int channelId, InputStream in, OutputStream out, Optional<Protocol.ClientOptions> opts)
        throws IOException {
        if (
            channelId < 0 || channelId >= cfg.udpChannels()
        ) throw new IOException("bad udp channel");
        log.info(
            "UDP channel " +
                channelId +
                " registered from " +
                sock.getRemoteSocketAddress()
        );
        udp.setWriter(channelId, out, opts);
        while (true) {
            Protocol.UdpFrame f = Protocol.readUdpFrame(in);
            udp.onFrame(channelId, f);
        }
    }

    private void handleTcp(InputStream in, OutputStream out)
        throws IOException {
        Protocol.TcpConnect c = Protocol.readTcpConnect(in);
        log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
        try (Socket remote = new Socket()) {
            remote.setTcpNoDelay(true);
            remote.setKeepAlive(true);
            remote.connect(new InetSocketAddress(c.ip(), c.port()), 10_000);

            InputStream rin = remote.getInputStream();
            OutputStream rout = remote.getOutputStream();
            OutputStream cout = out;

            Future<?> f1 = pumpPool.submit(() -> pump(in, rout));
            Future<?> f2 = pumpPool.submit(() -> pump(rin, cout));
            try {
                f1.get();
            } catch (Exception ignored) {}
            try {
                f2.get();
            } catch (Exception ignored) {}
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
