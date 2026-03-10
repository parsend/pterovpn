package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    private final Socket sock;
    private final Config cfg;
    private final UdpSessions udp;
    private final TcpReactorPool tcpPool;
  private final ExecutorService streamPool;

    ConnectionHandler(Socket sock, Config cfg, UdpSessions udp, TcpReactorPool tcpPool, ExecutorService streamPool) {
        this.sock = sock;
        this.cfg = cfg;
        this.udp = udp;
        this.tcpPool = tcpPool;
        this.streamPool = streamPool;
    }

    @Override
    public void run() {
        boolean handedOff = false;
        Socket s = sock;
        OutputStream rawOut = null;
        try {
            var xor = new XorStream(XorStream.keyFromToken(cfg.token()));
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            rawOut = s.getOutputStream();
            InputStream in = xor.wrapInput(new BufferedInputStream(s.getInputStream()));

            Protocol.HandshakeResult hr = Protocol.readHandshake(in);
            Protocol.Handshake hs = hr.handshake();
            if (
                !MessageDigest.isEqual(
                    cfg.token().getBytes(StandardCharsets.UTF_8),
                    hs.token().getBytes(StandardCharsets.UTF_8)
                )
            ) {
                log.warning("bad token from " + s.getRemoteSocketAddress());
                sendCapabilityByte(rawOut);
                throw new IOException("bad token");
            }
            OutputStream out = xor.wrapOutput(rawOut);
            log.info(
                "Accepted role=" +
                    hs.role() +
                    " from " +
                    s.getRemoteSocketAddress()
            );

            if (hs.role() == Protocol.ROLE_UDP) {
                s.setSoTimeout(0);
                streamPool.submit(() -> {
                    try {
                        handleUdp(hs.channelId(), in, out, hr.opts());
                    } catch (IOException e) {
                        log.fine("udp role ended: " + e.getMessage());
                    }
                });
                handedOff = true;
                return;
            }
            if (hs.role() == Protocol.ROLE_TCP) {
                handleTcp(in, s, xor);
                handedOff = true;
                return;
            }
            log.warning("unknown role " + hs.role() + " from " + s.getRemoteSocketAddress());
            throw new IOException("bad role");
        } catch (EOFException ignored) {
            sendCapabilityByte(rawOut);
        } catch (SocketTimeoutException e) {
            log.warning("handshake timeout from " + s.getRemoteSocketAddress() + " after " + HANDSHAKE_TIMEOUT_MS + "ms");
            sendCapabilityByte(rawOut);
        } catch (IOException e) {
            log.fine("conn closed: " + e.getMessage());
            sendCapabilityByte(rawOut);
        } finally {
            if (!handedOff) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static void sendCapabilityByte(OutputStream rawOut) {
        if (rawOut == null) return;
        try {
            rawOut.write(Ipv6Detect.hasIPv6() ? 1 : 0);
            rawOut.flush();
        } catch (Throwable ignored) {}
    }

    private void handleUdp(int channelId, InputStream in, OutputStream out, Optional<Protocol.ClientOptions> opts)
        throws IOException {
        UdpSessions.UdpChannelWriter writer = udp.createWriter(out, opts.orElse(null));
        try {
            if (
                channelId < 0 || channelId >= cfg.udpChannels()
            ) throw new IOException("bad udp channel");
            log.info(
                "UDP channel " +
                    channelId +
                    " registered from " +
                    sock.getRemoteSocketAddress()
            );
            while (true) {
                Protocol.UdpFrame f = Protocol.readUdpFrame(in);
                udp.onFrame(writer, channelId, f);
            }
        } finally {
            udp.removeWriter(writer);
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleTcp(InputStream in, Socket s, XorStream xor) throws IOException {
        Protocol.TcpConnect c = Protocol.readTcpConnect(in);
        log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
        if (tcpPool == null) {
            throw new IOException("tcp reactor unavailable");
        }
        int available = in.available();
        byte[] initialClientData = available > 0 ? in.readNBytes(available) : null;
        tcpPool.register(s, c, xor, initialClientData, true);
    }
}
