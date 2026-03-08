package dev.c0redev.pteravpn;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.StandardSocketOptions;
import java.util.Optional;
import java.util.logging.Logger;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);

    private final Socket sock;
    private final Config cfg;
    private final UdpSessions udp;
  private final Protocol.HandshakeResult preHandshake;
  private final byte[] prefilled;
  private final int initialReadPos;

    ConnectionHandler(Socket sock, Config cfg, UdpSessions udp) {
        this(sock, cfg, udp, null, new byte[0], 0);
    }

    ConnectionHandler(
      Socket sock,
      Config cfg,
      UdpSessions udp,
      Protocol.HandshakeResult preHandshake,
      byte[] prefilled,
      int initialReadPos
    ) {
        this.sock = sock;
        this.cfg = cfg;
        this.udp = udp;
        this.preHandshake = preHandshake;
        this.prefilled = prefilled != null ? prefilled : new byte[0];
        this.initialReadPos = initialReadPos;
    }

    @Override
    public void run() {
        try (Socket s = sock) {
            XorStream xor = new XorStream(
                XorStream.keyFromToken(cfg.token()),
                initialReadPos,
                0
            );
            PrefilledInputStream rawIn = new PrefilledInputStream(prefilled, s.getInputStream());
            InputStream in = xor.wrapInput(rawIn);
            OutputStream out = xor.wrapOutput(s.getOutputStream());

            Protocol.HandshakeResult hr = preHandshake != null ? preHandshake : Protocol.readHandshake(in);
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
                handleUdp(hs.channelId(), in, out, hr.opts());
                return;
            }
            if (hs.role() == Protocol.ROLE_TCP) {
                handleTcp(s, in, xor, rawIn);
                return;
            }
            throw new IOException("bad role");
        } catch (EOFException ignored) {
        } catch (IOException e) {
            log.info("conn closed: " + e.getMessage());
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

    private void handleTcp(Socket s, InputStream in, XorStream xor, PrefilledInputStream rawIn)
        throws IOException {
        Protocol.TcpConnect c = Protocol.readTcpConnect(in);
        log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
        byte[] initial = rawIn.remainder();
        try (SocketChannel remoteCh = SocketChannel.open()) {
            SocketChannel clientCh = s.getChannel();
            if (clientCh == null) {
                return;
            }
            remoteCh.setOption(StandardSocketOptions.TCP_NODELAY, true);
            remoteCh.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            try {
                remoteCh.connect(new InetSocketAddress(c.ip(), c.port()));
            } catch (IOException e) {
                log.warning("TCP upstream connect failed " + c.ip().getHostAddress() + ":" + c.port() + " " + e.getMessage());
                throw e;
            }
            clientCh.configureBlocking(false);
            remoteCh.configureBlocking(false);
            byte[] key = XorStream.keyFromToken(cfg.token());
            TcpRelayRelay relay = new TcpRelayRelay(
                clientCh,
                remoteCh,
                key,
                xor.readPosition(),
                xor.writePosition(),
                initial
            );
            relay.run();
        }
    }

    private static final class TcpRelayRelay {
        private final SocketChannel client;
        private final SocketChannel remote;
        private final byte[] key;
        private int c2sPos;
        private int s2cPos;
        private final ByteBuffer toRemote = ByteBuffer.wrap(BufferPool.borrow(BufferPool.LARGE_CAP));
        private final ByteBuffer toClient = ByteBuffer.wrap(BufferPool.borrow(BufferPool.LARGE_CAP));
        private final byte[] initial;
        private int initialPos;

        private TcpRelayRelay(
          SocketChannel client,
          SocketChannel remote,
          byte[] key,
          int c2sPos,
          int s2cPos,
          byte[] initial
        ) {
            this.client = client;
            this.remote = remote;
            this.key = key;
            this.c2sPos = c2sPos;
            this.s2cPos = s2cPos;
            this.initial = initial != null ? initial : new byte[0];
        }

        void run() throws IOException {
            try (Selector selector = Selector.open()) {
                SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);
                SelectionKey remoteKey = remote.register(selector, SelectionKey.OP_READ);
            toRemote.limit(0);
            toClient.limit(0);
                while (true) {
                if (initialPos < initial.length && !toRemote.hasRemaining()) {
                    if (!readFromClient()) {
                        return;
                    }
                }
                    int ready = selector.select(1000);
                    if (ready == 0) {
                        continue;
                    }
                    for (SelectionKey key : selector.selectedKeys()) {
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key == clientKey && key.isReadable()) {
                            if (!readFromClient()) {
                                return;
                            }
                        } else if (key == clientKey && key.isWritable()) {
                            if (!flushToRemote(remoteKey)) {
                                return;
                            }
                        }
                        if (key == remoteKey && key.isReadable()) {
                            if (!readFromRemote()) {
                                return;
                            }
                        } else if (key == remoteKey && key.isWritable()) {
                            if (!flushToClient(clientKey)) {
                                return;
                            }
                        }
                    }
                    selector.selectedKeys().clear();
                    int remoteOps = SelectionKey.OP_READ;
                    if (toRemote.hasRemaining()) {
                        remoteOps |= SelectionKey.OP_WRITE;
                    }
                    int clientOps = SelectionKey.OP_READ;
                    if (toClient.hasRemaining()) {
                        clientOps |= SelectionKey.OP_WRITE;
                    }
                    if (remoteKey.isValid()) {
                        remoteKey.interestOps(remoteOps);
                    }
                    if (clientKey.isValid()) {
                        clientKey.interestOps(clientOps);
                    }
                }
            } finally {
                close();
                BufferPool.release(toRemote.array());
                BufferPool.release(toClient.array());
            }
        }

        private boolean readFromClient() throws IOException {
            if (toRemote.hasRemaining()) {
                return true;
            }
            toRemote.clear();
            int n = fillFromClient();
            if (n < 0) {
                return false;
            }
            if (n == 0) {
                return true;
            }
            applyXorDecrypt(toRemote, n);
            toRemote.flip();
            if (!flush(toRemote, remote)) {
                return false;
            }
            return true;
        }

        private int fillFromClient() throws IOException {
            if (initialPos < initial.length) {
                int n = Math.min(initial.length - initialPos, toRemote.remaining());
                toRemote.put(initial, initialPos, n);
                initialPos += n;
                return n;
            }
            return client.read(toRemote);
        }

        private boolean readFromRemote() throws IOException {
            if (toClient.hasRemaining()) {
                return true;
            }
            toClient.clear();
            int n = remote.read(toClient);
            if (n == -1) {
                return false;
            }
            if (n <= 0) {
                return true;
            }
            applyXorEncrypt(toClient, n);
            toClient.flip();
            if (!flush(toClient, client)) {
                return false;
            }
            return true;
        }

        private boolean flushToRemote(SelectionKey remoteKey) throws IOException {
            if (!toRemote.hasRemaining()) {
                return true;
            }
            if (!flush(toRemote, remote)) {
                return false;
            }
            if (!remoteKey.isValid()) {
                return false;
            }
            return true;
        }

        private boolean flushToClient(SelectionKey clientKey) throws IOException {
            if (!toClient.hasRemaining()) {
                return true;
            }
            if (!flush(toClient, client)) {
                return false;
            }
            if (!clientKey.isValid()) {
                return false;
            }
            return true;
        }

        private boolean flush(ByteBuffer source, SocketChannel dst) throws IOException {
            while (source.hasRemaining()) {
                int n = dst.write(source);
                if (n < 0) {
                    return false;
                }
                if (n == 0) {
                    return true;
                }
            }
            source.clear();
            return true;
        }

        private void applyXorDecrypt(ByteBuffer buf, int len) {
            byte[] arr = buf.array();
            int off = buf.position() - len;
            for (int i = 0; i < len; i++) {
                arr[off + i] ^= key[c2sPos % key.length];
                c2sPos++;
            }
        }

        private void applyXorEncrypt(ByteBuffer buf, int len) {
            byte[] arr = buf.array();
            int off = buf.position() - len;
            for (int i = 0; i < len; i++) {
                arr[off + i] ^= key[s2cPos % key.length];
                s2cPos++;
            }
        }

        private void close() {
            try {
                client.close();
            } catch (IOException ignored) {}
            try {
                remote.close();
            } catch (IOException ignored) {}
        }
    }

    private static final class PrefilledInputStream extends InputStream {
        private final byte[] prefilled;
        private int prefilledPos;
        private final InputStream socketIn;

        private PrefilledInputStream(byte[] prefilled, InputStream socketIn) {
            this.prefilled = prefilled != null ? prefilled : new byte[0];
            this.socketIn = socketIn;
        }

        byte[] remainder() {
            if (prefilledPos >= prefilled.length) {
                return new byte[0];
            }
            byte[] r = new byte[prefilled.length - prefilledPos];
            System.arraycopy(prefilled, prefilledPos, r, 0, r.length);
            return r;
        }

        @Override
        public int read() throws IOException {
            if (prefilledPos < prefilled.length) {
                return prefilled[prefilledPos++] & 0xff;
            }
            return socketIn.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (prefilledPos < prefilled.length) {
                int n = Math.min(len, prefilled.length - prefilledPos);
                System.arraycopy(prefilled, prefilledPos, b, off, n);
                prefilledPos += n;
                return n;
            }
            return socketIn.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            int available = Math.max(0, prefilled.length - prefilledPos);
            return available + socketIn.available();
        }
    }
}
