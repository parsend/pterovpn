package dev.c0redev.pteravpn;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.logging.Logger;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);

    private final Socket sock;
    private final Config cfg;
    private final UdpSessions udp;
    private final TcpRelay tcpRelay;

    ConnectionHandler(Socket sock, Config cfg, UdpSessions udp, TcpRelay tcpRelay) {
        this.sock = sock;
        this.cfg = cfg;
        this.udp = udp;
        this.tcpRelay = tcpRelay;
    }

    @Override
    public void run() {
        boolean handoff = false;
        try {
            var xor = new XorStream(XorStream.keyFromToken(cfg.token()));
            InputStream in = xor.wrapInput(new BufferedInputStream(sock.getInputStream()));
            OutputStream out = xor.wrapOutput(sock.getOutputStream());

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
                sock.getRemoteSocketAddress()
            );

            if (hs.role() == Protocol.ROLE_UDP) {
                handleUdp(hs.channelId(), in, out, hr.opts());
                return;
            }
            if (hs.role() == Protocol.ROLE_TCP) {
                Protocol.TcpConnect tcp = Protocol.readTcpConnect(in);
                handoff = handleTcp(tcp, xor);
                return;
            }
            throw new IOException("bad role");
        } catch (EOFException ignored) {
        } catch (IOException e) {
            log.fine("conn closed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.fine("conn protocol error: " + e.getMessage());
        } finally {
            if (!handoff) {
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
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

    private boolean handleTcp(Protocol.TcpConnect tcp, XorStream xor)
        throws IOException {
        if (sock.getChannel() == null) {
            throw new IOException("client socket channel unavailable");
        }
        tcpRelay.register(sock.getChannel(), tcp.ip(), tcp.port(), xor);
        return true;
    }
}
