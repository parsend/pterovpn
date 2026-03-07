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
import java.util.concurrent.atomic.AtomicBoolean;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);

    private final Socket sock;
    private final Config cfg;
    private final UdpSessions udp;
    private final ExecutorService pumpPool;
    private final Protocol.HandshakeResult preParsed;
    private final byte[] preReadPrefix;

    ConnectionHandler(Socket sock, Config cfg, UdpSessions udp, ExecutorService pumpPool) {
        this(sock, cfg, udp, pumpPool, null, null);
    }

    ConnectionHandler(
        Socket sock,
        Config cfg,
        UdpSessions udp,
        ExecutorService pumpPool,
        Protocol.HandshakeResult preParsed,
        byte[] preReadPrefix
    ) {
        this.sock = sock;
        this.cfg = cfg;
        this.udp = udp;
        this.pumpPool = pumpPool;
        this.preParsed = preParsed;
        this.preReadPrefix = preReadPrefix;
    }

    @Override
    public void run() {
        try (Socket s = sock) {
            var xor = new XorStream(XorStream.keyFromToken(cfg.token()));
            InputStream socketIn = xor.wrapInput(new BufferedInputStream(s.getInputStream()));
            InputStream in = preReadPrefix == null ? socketIn : new PrefixedInputStream(preReadPrefix, socketIn);
            OutputStream out = xor.wrapOutput(s.getOutputStream());
            Protocol.HandshakeResult hr = preParsed == null ? Protocol.readHandshake(in) : preParsed;
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
                handleUdp(hr, in, out);
                return;
            }
            if (hs.role() == Protocol.ROLE_TCP) {
                handleTcp(in, out);
                return;
            }
            if (hs.role() == Protocol.ROLE_LOG) {
                handleLog(in, out);
                return;
            }
            throw new IOException("bad role");
        } catch (EOFException ignored) {
        } catch (IOException e) {
            log.fine("conn closed: " + e.getMessage());
        }
    }

    private void handleUdp(Protocol.HandshakeResult hr, InputStream in, OutputStream out)
        throws IOException {
        int channelId = hr.handshake().channelId();
        Optional<Protocol.ClientOptions> opts = hr.opts();
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

    private static final class PrefixedInputStream extends InputStream {
        private final byte[] prefix;
        private int prefixPos;
        private final InputStream delegate;

        PrefixedInputStream(byte[] prefix, InputStream delegate) {
            this.prefix = prefix == null ? new byte[0] : prefix;
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            if (prefixPos < prefix.length) {
                return prefix[prefixPos++] & 0xff;
            }
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (prefixPos < prefix.length) {
                int n = Math.min(len, prefix.length - prefixPos);
                System.arraycopy(prefix, prefixPos, b, off, n);
                prefixPos += n;
                if (n == len) {
                    return n;
                }
                int tail = delegate.read(b, off + n, len - n);
                return tail < 0 ? n : n + tail;
            }
            return delegate.read(b, off, len);
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
            AtomicBoolean closed = new AtomicBoolean(false);
            Runnable closeBoth = () -> {
                if (!closed.compareAndSet(false, true)) return;
                try {
                    remote.close();
                } catch (IOException ignored) {}
                try {
                    sock.close();
                } catch (IOException ignored) {}
            };

            Future<?> f1 = pumpPool.submit(() -> pump(in, rout, closeBoth));
            Future<?> f2 = pumpPool.submit(() -> pump(rin, cout, closeBoth));
            try {
                f1.get();
            } catch (Exception ignored) {}
            try {
                f2.get();
            } catch (Exception ignored) {}
        }
    }

    private void handleLog(InputStream in, OutputStream out) throws IOException {
        log.info("Log channel from " + sock.getRemoteSocketAddress());
        try (var subscription = ServerLogHub.subscribe(out)) {
            byte[] buf = new byte[1];
            while (in.read(buf) != -1) {
            }
        }
    }

    private static void pump(InputStream in, OutputStream out, Runnable onClose) {
        byte[] buf = new byte[32 * 1024];
        try {
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        } catch (IOException ignored) {
            log.fine("pump closed: " + ignored.getMessage());
        } finally {
            if (onClose != null) {
                onClose.run();
            }
        }
    }
}
