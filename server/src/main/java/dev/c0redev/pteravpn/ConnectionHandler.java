package dev.c0redev.pteravpn;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static volatile SSLContext tlsContext;
    private static final String TLS_PASSWORD = "changeit"; //<-- real change this

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
            BufferedInputStream rawBuf = new BufferedInputStream(s.getInputStream());
            InputStream in = xor.wrapInput(rawBuf);
            OutputStream out = xor.wrapOutput(rawOut);

            Protocol.HandshakeResult hr = Protocol.readHandshake(in);
            Protocol.Handshake hs = hr.handshake();
            String transport = hr.opts().map(Protocol.ClientOptions::transport).orElse("xor");
            String tlsName = hr.opts().map(Protocol.ClientOptions::tlsName).orElse("");
            boolean useTls = "tls".equalsIgnoreCase(transport);

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
            if (useTls) {
                int avail = rawBuf.available();
                byte[] leftover = avail > 0 ? rawBuf.readNBytes(avail) : new byte[0];
                Socket streamSocket = leftover.length > 0
                    ? new SocketWithStream(s, new SequenceInputStream(new ByteArrayInputStream(leftover), s.getInputStream()))
                    : s;
                SSLSocket tlsSocket = upgradeToTls(streamSocket, tlsName);
                s = tlsSocket;
                in = tlsSocket.getInputStream();
                out = tlsSocket.getOutputStream();
                xor = null;
            }
            log.info(
                "Accepted role=" +
                    hs.role() +
                    " from " +
                    s.getRemoteSocketAddress()
            );

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
        if (xor == null) {
            relayTcp(in, s, c);
            return;
        }
        if (tcpPool == null) {
            throw new IOException("tcp reactor unavailable");
        }
        int available = in.available();
        byte[] initialClientData = available > 0 ? in.readNBytes(available) : null;
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

    private void copyStreams(
        InputStream in,
        OutputStream out,
        Socket client,
        Socket remote,
        AtomicBoolean closed
    ) {
        byte[] buf = new byte[64 * 1024];
        try {
            while (true) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
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

    private static final class SocketWithStream extends Socket {
        private final Socket delegate;
        private final InputStream customIn;

        SocketWithStream(Socket delegate, InputStream customIn) throws IOException {
            super();
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
        public int getPort() { return delegate.getPort(); }

        @Override
        public java.net.InetAddress getInetAddress() { return delegate.getInetAddress(); }

        @Override
        public int getLocalPort() { return delegate.getLocalPort(); }

        @Override
        public java.net.InetAddress getLocalAddress() { return delegate.getLocalAddress(); }

        @Override
        public java.net.SocketAddress getRemoteSocketAddress() { return delegate.getRemoteSocketAddress(); }

        @Override
        public boolean isClosed() { return delegate.isClosed(); }

        @Override
        public boolean isConnected() { return delegate.isConnected(); }
    }

    private static SSLSocket upgradeToTls(Socket raw, String tlsName) throws IOException {
        SSLSocketFactory sf = tlsContext().getSocketFactory();
        String host = tlsName == null || tlsName.isBlank() ? raw.getInetAddress().getHostAddress() : tlsName;
        SSLSocket tlsSocket = (SSLSocket) sf.createSocket(
            raw,
            host,
            raw.getPort(),
            true
        );
        tlsSocket.setUseClientMode(false);
        tlsSocket.setSoTimeout(0);
        try {
            tlsSocket.startHandshake();
        } catch (IOException e) {
            tlsSocket.close();
            throw e;
        }
        return tlsSocket;
    }

    private static SSLContext tlsContext() throws IOException {
        SSLContext context = tlsContext;
        if (context != null) {
            return context;
        }
        try {
            synchronized (ConnectionHandler.class) {
                if (tlsContext != null) {
                    return tlsContext;
                }
                tlsContext = buildTlsContext();
                return tlsContext;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("tls context init failed", e);
        }
    }

    private static SSLContext buildTlsContext() throws GeneralSecurityException, IOException {
        try {
            Class<?> keyGenClass = Class.forName("sun.security.tools.keytool.CertAndKeyGen");
            Class<?> x500NameClass = Class.forName("sun.security.x509.X500Name");

            Object keyGen = keyGenClass
                .getConstructor(String.class, String.class, String.class)
                .newInstance("RSA", "SHA256withRSA", null);
            keyGenClass.getMethod("generate", int.class).invoke(keyGen, 2048);

            Object name = x500NameClass
                .getConstructor(String.class)
                .newInstance("CN=pteravpn");
            Object cert = keyGenClass
                .getMethod("getSelfCertificate", x500NameClass, long.class)
                .invoke(keyGen, name, 365L * 24L * 60L * 60L);

            java.security.PrivateKey privateKey =
                (java.security.PrivateKey) keyGenClass.getMethod("getPrivateKey").invoke(keyGen);

            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            ks.setKeyEntry(
                "pteravpn",
                privateKey,
                TLS_PASSWORD.toCharArray(),
                new java.security.cert.Certificate[]{(java.security.cert.Certificate) cert}
            );

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, TLS_PASSWORD.toCharArray());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(
                kmf.getKeyManagers(),
                null,
                new SecureRandom()
            );
            return ctx;
        } catch (ReflectiveOperationException e) {
            throw new GeneralSecurityException("tls context reflection failed", e);
        }
    }
}
