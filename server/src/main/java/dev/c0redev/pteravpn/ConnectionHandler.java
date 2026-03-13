package dev.c0redev.pteravpn;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static volatile MtlsMaterial mtlsMaterial;

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
        boolean xorTransport = true;
        InputStream in = null;
        OutputStream out = null;
        try {
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            rawOut = s.getOutputStream();
            BufferedInputStream rawIn = new BufferedInputStream(s.getInputStream());
            String transportName = Protocol.readTransportPreface(rawIn);
            xorTransport = !"mtls".equals(transportName);

            XorStream xor = null;
            TlsTunnel tlsTunnel = null;
            if (xorTransport) {
                xor = new XorStream(XorStream.keyFromToken(cfg.token()));
                in = xor.wrapInput(rawIn);
                out = xor.wrapOutput(rawOut);
            } else {
                byte[] peek = peek(rawIn, 6);
                if (peek.length < 6) {
                    throw new EOFException();
                }
                if (!looksLikeTlsClientHello(peek)) {
                    log.warning("mtls preface with non-tls payload from " + s.getRemoteSocketAddress() + ", fallback to xor");
                    transportName = "xor-fallback";
                    xorTransport = true;
                    xor = new XorStream(XorStream.keyFromToken(cfg.token()));
                    in = xor.wrapInput(rawIn);
                    out = xor.wrapOutput(rawOut);
                } else {
                    int recordLen = ((peek[3] & 0xff) << 8) | (peek[4] & 0xff);
                    if (recordLen <= 0 || recordLen > 16384) {
                        throw new IOException("mtls: invalid record length");
                    }
                    byte[] rest = Protocol.readN(rawIn, recordLen);
                    byte[] initialTls = new byte[6 + recordLen];
                    System.arraycopy(peek, 0, initialTls, 0, 6);
                    System.arraycopy(rest, 0, initialTls, 6, recordLen);
                    SocketChannel ch = s.getChannel();
                    if (ch == null) {
                        throw new IOException("mtls: socket has no channel");
                    }
                    SSLContext ctx = mtlsMaterial(cfg).serverContext();
                    SSLEngine engine = ctx.createSSLEngine();
                    engine.setUseClientMode(false);
                    engine.setNeedClientAuth(true);
                    tlsTunnel = new TlsTunnel(ch, engine);
                    tlsTunnel.setInitialEncrypted(initialTls);
                    tcpPool.registerMtlsPending(ch, tlsTunnel, cfg.token());
                    handedOff = true;
                    return;
                }
            }

            Protocol.HandshakeResult hr = Protocol.readHandshake(in);
            Protocol.Handshake hs = hr.handshake();
            String expectedToken = cfg.token();
            String receivedToken = hs.token();
            byte[] expectedTokenBytes = expectedToken.getBytes(StandardCharsets.UTF_8);
            byte[] receivedTokenBytes = receivedToken.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expectedTokenBytes, receivedTokenBytes)) {
                log.warning("bad token from " + s.getRemoteSocketAddress()
                    + " (recvLen=" + receivedTokenBytes.length
                    + " expectLen=" + expectedTokenBytes.length
                    + " recv=\"" + receivedToken + "\""
                    + " expect=\"" + expectedToken + "\")");
                sendCapabilityByte(xorTransport ? rawOut : out);
                try {
                    s.shutdownOutput();
                } catch (IOException ignored) {}
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("bad token");
            }

            if (hs.role() == Protocol.ROLE_BOOTSTRAP) {
                if (!xorTransport) throw new IOException("bootstrap requires xor");
                writeBootstrapBundle(out, mtlsMaterial(cfg));
                out.flush();
                handedOff = true;
                return;
            }

            log.info("Accepted role=" + hs.role() + " transport=" + transportName + " from " + s.getRemoteSocketAddress());

            if (hs.role() == Protocol.ROLE_UDP) {
                final InputStream fin = in;
                final OutputStream fout = out;
                s.setSoTimeout(0);
                streamPool.submit(() -> {
                    try {
                        handleUdp(hs.channelId(), fin, fout, hr.opts());
                    } catch (IOException e) {
                        log.fine("udp role ended: " + e.getMessage());
                    }
                });
                handedOff = true;
                return;
            }
            if (hs.role() == Protocol.ROLE_TCP) {
                handleTcp(in, s, xor, tlsTunnel);
                handedOff = true;
                return;
            }
            log.warning("unknown role " + hs.role() + " from " + s.getRemoteSocketAddress());
            throw new IOException("bad role");
        } catch (EOFException ignored) {
            sendCapabilityByte(out != null ? out : (xorTransport ? rawOut : null));
        } catch (SocketTimeoutException e) {
            log.warning("handshake timeout from " + s.getRemoteSocketAddress() + " after " + HANDSHAKE_TIMEOUT_MS + "ms");
            sendCapabilityByte(out != null ? out : (xorTransport ? rawOut : null));
        } catch (IOException e) {
            if (xorTransport) {
                log.fine("conn closed: " + e.getMessage());
            } else {
                log.warning("mtls conn error from " + s.getRemoteSocketAddress() + ": " + e.getMessage());
            }
            sendCapabilityByte(out != null ? out : (xorTransport ? rawOut : null));
        } finally {
            if (!handedOff) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static MtlsMaterial mtlsMaterial(Config cfg) throws IOException {
        MtlsMaterial material = mtlsMaterial;
        if (material != null) return material;
        synchronized (ConnectionHandler.class) {
            if (mtlsMaterial == null) {
                mtlsMaterial = MtlsMaterial.loadOrCreate(cfg.baseDir());
            }
            return mtlsMaterial;
        }
    }

    private static void sendCapabilityByte(OutputStream rawOut) {
        if (rawOut == null) return;
        try {
            rawOut.write(Ipv6Detect.hasIPv6() ? 1 : 0);
            rawOut.flush();
        } catch (Throwable ignored) {}
    }

    private static byte[] peek(BufferedInputStream in, int n) throws IOException {
        in.mark(n);
        byte[] buf = in.readNBytes(n);
        in.reset();
        return buf;
    }

    private static boolean looksLikeTlsClientHello(byte[] h) {
        if (h == null || h.length < 6) return false;
        int type = h[0] & 0xff;
        int major = h[1] & 0xff;
        int minor = h[2] & 0xff;
        int len = ((h[3] & 0xff) << 8) | (h[4] & 0xff);
        int handshakeType = h[5] & 0xff;
        if (type != 0x16) return false;
        if (major != 0x03) return false;
        if (minor < 0x00 || minor > 0x04) return false;
        if (len <= 0 || len > 16709) return false;
        return handshakeType == 0x01;
    }

    private void handleUdp(int channelId, InputStream in, OutputStream out, Optional<Protocol.ClientOptions> opts)
        throws IOException {
        UdpSessions.UdpChannelWriter writer = udp.createWriter(out, opts.orElse(null));
        try {
            if (channelId < 0 || channelId >= cfg.udpChannels()) throw new IOException("bad udp channel");
            log.info("UDP channel " + channelId + " registered from " + sock.getRemoteSocketAddress());
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

    private void handleTcp(InputStream in, Socket s, XorStream xor, TlsTunnel tlsTunnel) throws IOException {
        Protocol.TcpConnect c = Protocol.readTcpConnect(in);
        log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
        if (tcpPool == null) {
            relayTcp(in, s, c);
            return;
        }
        int available = in.available();
        byte[] initialClientData = available > 0 ? in.readNBytes(available) : null;
        if (tlsTunnel != null) {
            tcpPool.register(tlsTunnel.channel(), c, null, tlsTunnel, initialClientData, true);
            return;
        }
        if (xor == null) {
            relayTcp(in, s, c);
            return;
        }
        tcpPool.register(s, c, xor, initialClientData, true);
    }

    private void relayTcp(InputStream in, Socket client, Protocol.TcpConnect connect) throws IOException {
        Socket remote = new Socket();
        try {
            remote.connect(new InetSocketAddress(connect.ip(), connect.port()));
            remote.setTcpNoDelay(true);
            remote.setKeepAlive(true);

            InputStream remoteIn = remote.getInputStream();
            OutputStream remoteOut = remote.getOutputStream();
            OutputStream clientOut = client.getOutputStream();
            int available = in.available();
            if (available > 0) {
                remoteOut.write(in.readNBytes(available));
            }
            remoteOut.flush();

            AtomicBoolean closed = new AtomicBoolean(false);
            Thread clientToRemote = new Thread(
                () -> copyStreams(in, remoteOut, client, remote, closed),
                "pteravpn-tcp-c2r"
            );
            Thread remoteToClient = new Thread(
                () -> copyStreams(remoteIn, clientOut, client, remote, closed),
                "pteravpn-tcp-r2c"
            );
            clientToRemote.start();
            remoteToClient.start();
            clientToRemote.join();
            remoteToClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            closeQuietly(remote);
        }
    }

    private void copyStreams(InputStream in, OutputStream out, Socket client, Socket remote, AtomicBoolean closed) {
        byte[] buf = new byte[64 * 1024];
        try {
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(client);
                closeQuietly(remote);
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static SSLSocket upgradeToMtls(Socket raw, BufferedInputStream rawIn, Config cfg) throws IOException {
        int available = rawIn.available();
        Socket source = raw;
        if (available > 0) {
            byte[] leftover = rawIn.readNBytes(available);
            source = new SocketWithStream(raw, new java.io.SequenceInputStream(
                new ByteArrayInputStream(leftover),
                raw.getInputStream()
            ));
        }
        SSLContext context = mtlsMaterial(cfg).serverContext();
        SSLSocketFactory sf = context.getSocketFactory();
        String host = raw.getInetAddress().getHostAddress();
        SSLSocket tlsSocket = (SSLSocket) sf.createSocket(source, host, raw.getPort(), true);
        tlsSocket.setUseClientMode(false);
        tlsSocket.setNeedClientAuth(true);
        tlsSocket.setSoTimeout(0);
        try {
            tlsSocket.startHandshake();
        } catch (IOException e) {
            tlsSocket.close();
            throw e;
        }
        return tlsSocket;
    }

    private static void writeBootstrapBundle(OutputStream out, MtlsMaterial material) throws IOException {
        writeBlob(out, material.serverCertPem());
        writeBlob(out, material.clientCertPem());
        writeBlob(out, material.clientKeyPem());
    }

    private static void writeBlob(OutputStream out, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        Protocol.writeU32(out, len);
        if (len > 0) {
            out.write(data);
        }
    }

    private static final class SocketWithStream extends Socket {
        private final Socket delegate;
        private final InputStream customIn;

        SocketWithStream(Socket delegate, InputStream customIn) {
            this.delegate = delegate;
            this.customIn = customIn;
        }

        @Override
        public InputStream getInputStream() {
            return customIn;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public java.net.InetAddress getInetAddress() {
            return delegate.getInetAddress();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public java.net.SocketAddress getRemoteSocketAddress() {
            return delegate.getRemoteSocketAddress();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }
    }
}
