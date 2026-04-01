package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final SecureRandom HELLO_RND = new SecureRandom();
    
    private static final String PROBE_HANDSHAKE_TOKEN = "probe-bad-token";

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
                if (PROBE_HANDSHAKE_TOKEN.equals(hs.token())) {
                    log.info("probe handshake (caps read) from " + s.getRemoteSocketAddress());
                } else {
                    log.warning("bad token from " + s.getRemoteSocketAddress());
                }
                sendCapability(rawOut);
                throw new IOException("bad token");
            }
            OutputStream out = xor.wrapOutput(rawOut);
            s.setSoTimeout(0);
            var session = new SessionHandler(cfg, udp, String.valueOf(s.getRemoteSocketAddress()), () -> {
                try {
                    s.close();
                } catch (IOException ignored) {}
            });
            session.handle(hs, hr, in, out, (connect, rest) -> handleTcp(connect, rest, s, xor), streamPool);
            handedOff = true;
            return;
        } catch (EOFException ignored) {
            sendCapability(rawOut);
        } catch (SocketTimeoutException e) {
            log.warning("handshake timeout from " + s.getRemoteSocketAddress() + " after " + HANDSHAKE_TIMEOUT_MS + "ms");
            sendCapability(rawOut);
        } catch (IOException e) {
            log.fine("conn closed: " + e.getMessage());
            sendCapability(rawOut);
        } finally {
            if (!handedOff) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private void sendCapability(OutputStream rawOut) {
        if (rawOut == null) return;
        try {
            int legacyIpv6 = Ipv6Detect.hasIPv6() ? 1 : 0;
            int transportMask = 0;
            if (cfg.tcpEnabled()) transportMask |= Protocol.TRANSPORT_TCP;
            if (cfg.quicEnabled()) transportMask |= Protocol.TRANSPORT_QUIC;
            int featureBits = legacyIpv6 == 1 ? Protocol.FEAT_IPV6 : 0;
            int tcpPortHint = cfg.listenPorts().isEmpty() ? 0 : cfg.listenPorts().get(0);
            byte[] nonce = new byte[8];
            HELLO_RND.nextBytes(nonce);
            Protocol.writeServerHelloCaps(rawOut, new Protocol.ServerHelloCaps(
                Protocol.CAPS_VERSION,
                legacyIpv6,
                transportMask,
                featureBits,
                cfg.quicEnabled() ? cfg.quicListenPort() : 0,
                tcpPortHint,
                0,
                nonce
            ));
        } catch (Throwable ignored) {}
    }

    private void handleTcp(Protocol.TcpConnect c, InputStream in, Socket s, XorStream xor) throws IOException {
        log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
        if (tcpPool == null) {
            throw new IOException("tcp reactor unavailable");
        }
        s.setSoTimeout(0);
        byte[] initialClientData = drainAvailableWithoutBlocking(in);
        tcpPool.register(s, c, xor, initialClientData, true);
    }

    private static byte[] drainAvailableWithoutBlocking(InputStream in) throws IOException {
        if (in.available() <= 0) return null;
        var acc = new ByteArrayOutputStream();
        byte[] scratch = new byte[8192];
        while (in.available() > 0) {
            int want = Math.min(scratch.length, in.available());
            int r = in.read(scratch, 0, want);
            if (r <= 0) break;
            acc.write(scratch, 0, r);
        }
        return acc.size() == 0 ? null : acc.toByteArray();
    }
}
