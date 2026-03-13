package dev.c0redev.pteravpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class TcpReactorPool {

    private static final Logger log = Log.logger(TcpReactorPool.class);
    private final Reactor[] reactors;
    private final AtomicInteger rr = new AtomicInteger();

    TcpReactorPool(int workers) throws IOException {
        int n = Math.max(1, workers);
        this.reactors = new Reactor[n];
        for (int i = 0; i < n; i++) {
            reactors[i] = new Reactor("tcp-reactor-" + i);
        }
    }

    void register(
        Socket socket,
        Protocol.TcpConnect connect,
        XorStream xor,
        byte[] initialClientData,
        boolean initialClientDataDecrypted
    ) throws IOException {
        SocketChannel client = socket.getChannel();
        if (client == null) {
            throw new IOException("socket does not support channel");
        }
        register(client, connect, xor, null, initialClientData, initialClientDataDecrypted);
    }

    void register(
        SocketChannel client,
        Protocol.TcpConnect connect,
        XorStream xor,
        TlsTunnel tls,
        byte[] initialClientData,
        boolean initialClientDataDecrypted
    ) throws IOException {
        int idx = Math.floorMod(rr.getAndIncrement(), reactors.length);
        reactors[idx].register(
            client,
            connect,
            xor,
            tls,
            initialClientData,
            initialClientDataDecrypted,
            null
        );
    }

    void registerMtlsPending(SocketChannel client, TlsTunnel tls, String expectedToken) throws IOException {
        int idx = Math.floorMod(rr.getAndIncrement(), reactors.length);
        reactors[idx].registerMtlsPending(client, tls, expectedToken);
    }

    void shutdown() {
        for (Reactor reactor : reactors) {
            reactor.shutdown();
        }
    }

    private static final class Reactor {

        private static final int BUFFER_SIZE = 32 * 1024;
        private static final long CONNECT_TIMEOUT_MS = 10_000;
        private static final int MAX_PENDING_BYTES = 4 * 1024 * 1024;
        private static final Logger log = Log.logger(Reactor.class);

        private final Selector selector;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final ConcurrentLinkedQueue<Registration> pending =
            new ConcurrentLinkedQueue<>();
        private final Thread thread;

        private Reactor(String name) throws IOException {
            this.selector = Selector.open();
            this.thread = new Thread(this::run, name);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void register(
            SocketChannel client,
            Protocol.TcpConnect connect,
            XorStream xor,
            TlsTunnel tls,
            byte[] initialClientData,
            boolean initialClientDataDecrypted,
            String expectedToken
        ) throws IOException {
            if (closed.get()) {
                throw new IOException("reactor closed");
            }
            pending.add(
                new Registration(
                    client,
                    connect,
                    xor,
                    tls,
                    initialClientData,
                    initialClientDataDecrypted,
                    expectedToken
                )
            );
            selector.wakeup();
        }

        void registerMtlsPending(SocketChannel client, TlsTunnel tls, String expectedToken) throws IOException {
            if (closed.get()) {
                throw new IOException("reactor closed");
            }
            pending.add(
                new Registration(client, null, null, tls, null, true, expectedToken)
            );
            selector.wakeup();
        }

        void shutdown() {
            if (!closed.compareAndSet(false, true)) return;
            closePendingChannels();
            thread.interrupt();
            selector.wakeup();
        }

        private void run() {
            while (!closed.get()) {
                try {
                    selector.select(500);
                    processTimeouts(System.nanoTime());
                    drainPending();
                    processSelectedKeys();
                } catch (Exception e) {
                    if (!closed.get()) {
                        log.log(Level.WARNING, "reactor loop error", e);
                    }
                }
            }
            closeAllSessions();
            closePendingChannels();
            try {
                selector.close();
            } catch (IOException ignored) {}
        }

        private void closePendingChannels() {
            while (true) {
                Registration reg = pending.poll();
                if (reg == null) return;
                if (reg.tls() != null) {
                    reg.tls().close();
                } else {
                    closeChannel(reg.client());
                }
            }
        }

        private void closeRegistration(Registration reg) {
            if (reg.tls() != null) reg.tls().close();
            else closeChannel(reg.client());
        }

        private void processSelectedKeys() {
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;

                Object att = key.attachment();
                if (att instanceof PendingMtlsSession pending) {
                    try {
                        int readyOps = key.readyOps();
                        if ((readyOps & SelectionKey.OP_READ) != 0) pending.onReadable(key);
                        if ((readyOps & SelectionKey.OP_WRITE) != 0) pending.onWritable(key);
                    } catch (Exception e) {
                        log.log(Level.WARNING, "mtls pending key error", e);
                        pending.close("mtls pending error");
                    }
                    continue;
                }
                if (!(att instanceof Session session)) continue;
                try {
                    int readyOps = key.readyOps();
                    if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                        session.onConnectable();
                    }
                    if ((readyOps & SelectionKey.OP_READ) != 0) {
                        session.onReadable(key);
                    }
                    if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                        session.onWritable(key);
                    }
                } catch (Exception e) {
                    log.log(Level.WARNING, "reactor session key error", e);
                    session.close("session key error");
                }
            }
        }

        private void drainPending() {
            while (true) {
                Registration reg = pending.poll();
                if (reg == null) return;
                if (reg.expectedToken() != null) {
                    try {
                        SocketChannel client = reg.client();
                        client.configureBlocking(false);
                        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        client.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                        PendingMtlsSession pendingSession = new PendingMtlsSession(
                            selector, client, reg.tls(), reg.expectedToken(), this
                        );
                        int ops = SelectionKey.OP_READ;
                        if (reg.tls().hasPendingOutput()) ops |= SelectionKey.OP_WRITE;
                        SelectionKey key = client.register(selector, ops, pendingSession);
                        pendingSession.setClientKey(key);
                        try {
                            pendingSession.onReadable(key);
                        } catch (IOException e) {
                            closeRegistration(reg);
                            if (!closed.get()) log.warning("mtls pending first read failed: " + e.getMessage());
                        }
                    } catch (IOException e) {
                        closeRegistration(reg);
                        if (!closed.get()) log.warning("mtls pending register failed: " + e.getMessage());
                    }
                    continue;
                }
                SocketChannel remote = null;
                try {
                    SocketChannel client = reg.client();
                    client.configureBlocking(false);
                    client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    client.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

                    remote = SocketChannel.open();
                    remote.configureBlocking(false);
                    remote.setOption(StandardSocketOptions.TCP_NODELAY, true);

                    Protocol.TcpConnect connect = reg.connect();
                    boolean connected = remote.connect(
                        new InetSocketAddress(connect.ip(), connect.port())
                    );

                    Session session = new Session(
                        reg.xor(),
                        reg.tls(),
                        reg.initialClientData(),
                        reg.initialClientDataDecrypted()
                    );
                    session.setClientKey(
                        client.register(selector, SelectionKey.OP_READ, session)
                    );
                    SelectionKey remoteKey = remote.register(
                        selector,
                        connected
                            ? SelectionKey.OP_READ
                            : SelectionKey.OP_CONNECT,
                        session
                    );
                    session.setRemoteKey(remoteKey);
                    session.setRemote(remote);

                    if (connected) {
                        session.onConnected();
                    }
                } catch (IOException e) {
                    closeChannel(reg.client());
                    closeChannel(remote);
                    if (!closed.get()) {
                        log.warning("tcp register failed: " + e.getMessage());
                    }
                }
            }
        }

        private void processTimeouts(long nowNanos) {
            try {
                for (SelectionKey key : selector.keys()) {
                    if (!key.isValid()) continue;
                    if (
                        !(key.attachment() instanceof Session session)
                    ) continue;
                    if (
                        session.isConnecting() &&
                        nowNanos > session.connectDeadlineNanos()
                    ) {
                        session.close("tcp connect timeout");
                    }
                }
            } catch (Exception e) {}
        }

        private void closeAllSessions() {
            try {
                for (SelectionKey key : selector.keys()) {
                    if (key.attachment() instanceof Session session) {
                        session.close("reactor shutdown");
                    }
                }
            } catch (Exception ignored) {}
        }

        private static void closeChannel(SocketChannel ch) {
            if (ch == null) return;
            try {
                ch.close();
            } catch (IOException ignored) {}
        }

        void promoteToSession(
            SocketChannel client,
            SelectionKey clientKey,
            TlsTunnel tls,
            Protocol.TcpConnect connect,
            byte[] initialClientData,
            SocketChannel remote
        ) throws IOException {
            Session session = new Session(null, tls, initialClientData, true);
            session.setClientKey(clientKey);
            clientKey.attach(session);
            boolean connected = !remote.isConnectionPending();
            SelectionKey remoteKey = remote.register(
                selector,
                connected ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT,
                session
            );
            session.setRemoteKey(remoteKey);
            session.setRemote(remote);
            session.onConnected();
        }

        private record Registration(
            SocketChannel client,
            Protocol.TcpConnect connect,
            XorStream xor,
            TlsTunnel tls,
            byte[] initialClientData,
            boolean initialClientDataDecrypted,
            String expectedToken
        ) {}

        private final class PendingMtlsSession {

            private static final int APP_BUF_SIZE = 64 * 1024;

            private final Selector selector;
            private final SocketChannel client;
            private final TlsTunnel tls;
            private final String expectedToken;
            private final Reactor reactor;
            private final ByteBuffer appBuffer = ByteBuffer.allocate(APP_BUF_SIZE);
            private final ByteBuffer readBuf = ByteBuffer.allocate(BUFFER_SIZE);
            private SelectionKey clientKey;
            private boolean closed;

            PendingMtlsSession(Selector selector, SocketChannel client, TlsTunnel tls, String expectedToken, Reactor reactor) {
                this.selector = selector;
                this.client = client;
                this.tls = tls;
                this.expectedToken = expectedToken;
                this.reactor = reactor;
            }

            void setClientKey(SelectionKey key) {
                this.clientKey = key;
            }

            void onReadable(SelectionKey key) throws IOException {
                if (closed) return;
                if (!tls.isHandshakeComplete()) {
                    TlsTunnel.HandshakeStep step = tls.doHandshakeNonBlocking();
                    if (step == TlsTunnel.HandshakeStep.NEED_WRITE) {
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                        return;
                    }
                    if (step != TlsTunnel.HandshakeStep.DONE) return;
                }
                readBuf.clear();
                int n = tls.read(readBuf);
                if (n == -1) {
                    close("client closed");
                    return;
                }
                if (n == 0) return;
                readBuf.flip();
                if (appBuffer.remaining() < n) appBuffer.compact();
                appBuffer.put(readBuf);
                appBuffer.flip();
                Protocol.HandshakeResult hr = Protocol.tryReadHandshake(appBuffer);
                if (hr == null) {
                    appBuffer.compact();
                    return;
                }
                byte[] receivedTokenBytes = hr.handshake().token().getBytes(StandardCharsets.UTF_8);
                byte[] expectedTokenBytes = expectedToken.getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expectedTokenBytes, receivedTokenBytes)) {
                    close("bad token");
                    return;
                }
                if (hr.handshake().role() != Protocol.ROLE_TCP) {
                    close("bad role");
                    return;
                }
                Protocol.TcpConnect connect = Protocol.tryReadTcpConnect(appBuffer);
                if (connect == null) {
                    appBuffer.compact();
                    return;
                }
                byte[] initialData = null;
                if (appBuffer.hasRemaining()) {
                    initialData = new byte[appBuffer.remaining()];
                    appBuffer.get(initialData);
                }
                log.info("Accepted role=2 transport=mtls from " + client.getRemoteAddress());
                log.info("TCP connect to " + connect.ip().getHostAddress() + ":" + connect.port());
                SocketChannel remote = null;
                try {
                    remote = SocketChannel.open();
                    remote.configureBlocking(false);
                    remote.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    boolean connected = remote.connect(new InetSocketAddress(connect.ip(), connect.port()));
                    closed = true;
                    reactor.promoteToSession(client, key, tls, connect, initialData, remote);
                } catch (IOException e) {
                    closeChannel(remote);
                    close("remote connect failed: " + e.getMessage());
                }
            }

            void onWritable(SelectionKey key) throws IOException {
                if (closed) return;
                if (!tls.isHandshakeComplete()) {
                    TlsTunnel.HandshakeStep step = tls.doHandshakeNonBlocking();
                    if (step == TlsTunnel.HandshakeStep.NEED_READ) {
                        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                        return;
                    }
                    if (step == TlsTunnel.HandshakeStep.DONE) {
                        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    }
                    return;
                }
                if (tls.flushPending()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                }
            }

            void close(String reason) {
                if (closed) return;
                closed = true;
                log.fine("mtls pending close: " + reason);
                if (clientKey != null) clientKey.cancel();
                tls.close();
            }
        }

        private static final class Session {

            private static final long CONNECT_TIMEOUT_NANOS =
                TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
            private static final Logger log = Log.logger(Session.class);

            private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            private final ArrayDeque<ByteBuffer> toClient = new ArrayDeque<>();
            private final ArrayDeque<ByteBuffer> toRemote = new ArrayDeque<>();
            private final XorStream xor;
            private final TlsTunnel tls;
            private final AtomicBoolean closed = new AtomicBoolean(false);

            private SelectionKey clientKey;
            private SelectionKey remoteKey;
            private SocketChannel remote;
            private boolean connected;
            private boolean connecting = true;
            private final long connectDeadlineNanos =
                System.nanoTime() + CONNECT_TIMEOUT_NANOS;
            private long pendingToClientBytes;
            private long pendingToRemoteBytes;

            Session(
                XorStream xor,
                TlsTunnel tls,
                byte[] initialClientData,
                boolean initialClientDataDecrypted
            ) {
                this.xor = xor;
                this.tls = tls;
                if (initialClientData != null && initialClientData.length > 0) {
                    if (xor != null && !initialClientDataDecrypted) {
                        xor.decode(
                            initialClientData,
                            0,
                            initialClientData.length
                        );
                    }
                    toRemote.add(ByteBuffer.wrap(initialClientData));
                    pendingToRemoteBytes += initialClientData.length;
                }
            }

            void setClientKey(SelectionKey key) {
                this.clientKey = key;
            }

            void setRemoteKey(SelectionKey key) {
                this.remoteKey = key;
            }

            void setRemote(SocketChannel remote) {
                this.remote = remote;
            }

            boolean isConnecting() {
                return connecting;
            }

            long connectDeadlineNanos() {
                return connectDeadlineNanos;
            }

            void onConnected() {
                connected = true;
                connecting = false;
                if (remoteKey != null && remoteKey.isValid()) {
                    updateRemoteInterest();
                }
                updateClientInterest();
            }

            void onConnectable() {
                if (closed.get() || !connecting) return;
                try {
                    if (remote.finishConnect()) {
                        onConnected();
                    }
                } catch (IOException e) {
                    close("remote connect failed");
                }
            }

            void onReadable(SelectionKey key) {
                if (closed.get()) return;
                if (key == remoteKey && !connected) return;
                if (key == clientKey) {
                    readFromClient();
                } else if (key == remoteKey) {
                    readFromRemote();
                }
            }

            void onWritable(SelectionKey key) {
                if (closed.get()) return;
                if (key == clientKey) {
                    flushToClient();
                } else if (key == remoteKey) {
                    if (connected) {
                        flushToRemote();
                    }
                }
            }

            private void readFromClient() {
                try {
                    if (clientKey == null || !clientKey.isValid()) return;
                    if (pendingToRemoteBytes >= MAX_PENDING_BYTES) {
                        setInterestOps(
                            clientKey,
                            clientKey.interestOps() & ~SelectionKey.OP_READ
                        );
                        return;
                    }
                    buffer.clear();
                    int n;
                    if (tls != null) {
                        n = tls.read(buffer);
                    } else {
                        SocketChannel client = (SocketChannel) clientKey.channel();
                        n = client.read(buffer);
                    }
                    if (n == -1) {
                        close("client closed");
                        return;
                    }
                    if (n == 0) return;

                    buffer.flip();
                    byte[] data = new byte[n];
                    buffer.get(data);
                    if (xor != null) {
                        xor.decode(data, 0, n);
                    }
                    toRemote.add(ByteBuffer.wrap(data));
                    pendingToRemoteBytes += n;
                    if (pendingToRemoteBytes >= MAX_PENDING_BYTES) {
                        setInterestOps(
                            clientKey,
                            clientKey.interestOps() & ~SelectionKey.OP_READ
                        );
                    }
                    if (connected && remoteKey != null && remoteKey.isValid()) {
                        updateRemoteInterest();
                    }
                } catch (IOException e) {
                    close("client read failed");
                } catch (RuntimeException e) {
                    close("client read runtime failed");
                }
            }

            private void readFromRemote() {
                try {
                    if (
                        remote == null ||
                        (remoteKey != null && !remoteKey.isValid())
                    ) return;
                    if (pendingToClientBytes >= MAX_PENDING_BYTES) {
                        setInterestOps(
                            remoteKey,
                            remoteKey.interestOps() & ~SelectionKey.OP_READ
                        );
                        return;
                    }
                    int n = remote.read(buffer.clear());
                    if (n == -1) {
                        close("remote closed");
                        return;
                    }
                    if (n == 0) return;

                    buffer.flip();
                    byte[] data = new byte[n];
                    buffer.get(data);
                    if (xor != null) {
                        xor.encode(data, 0, n);
                    }
                    toClient.add(ByteBuffer.wrap(data));
                    pendingToClientBytes += n;
                    if (pendingToClientBytes >= MAX_PENDING_BYTES) {
                        setInterestOps(
                            remoteKey,
                            remoteKey.interestOps() & ~SelectionKey.OP_READ
                        );
                    }
                    if (clientKey != null && clientKey.isValid()) {
                        updateClientInterest();
                    }
                } catch (IOException e) {
                    close("remote read failed");
                } catch (RuntimeException e) {
                    close("remote read runtime failed");
                }
            }

            private void flushToClient() {
                if (clientKey == null || !clientKey.isValid()) return;
                if (tls != null) {
                    flushTlsToClient();
                } else {
                    flush(clientKey, toClient, clientKey.channel(), c -> {
                        pendingToClientBytes -= c;
                        updateRemoteInterest();
                    });
                }
                updateClientInterest();
            }

            private void flushTlsToClient() {
                try {
                    while (tls.hasPendingOutput()) {
                        if (!tls.flushPending()) {
                            setInterestOps(
                                clientKey,
                                clientKey.interestOps() | SelectionKey.OP_WRITE
                            );
                            return;
                        }
                    }
                    while (!toClient.isEmpty()) {
                        ByteBuffer b = toClient.peek();
                        int consumed = tls.write(b);
                        if (consumed < 0) {
                            close("tls write closed");
                            return;
                        }
                        if (consumed == 0) {
                            setInterestOps(
                                clientKey,
                                clientKey.interestOps() | SelectionKey.OP_WRITE
                            );
                            return;
                        }
                        pendingToClientBytes -= consumed;
                        if (!b.hasRemaining()) {
                            toClient.poll();
                        }
                        updateRemoteInterest();
                        if (tls.hasPendingOutput()) {
                            setInterestOps(
                                clientKey,
                                clientKey.interestOps() | SelectionKey.OP_WRITE
                            );
                            return;
                        }
                    }
                    setInterestOps(
                        clientKey,
                        clientKey.interestOps() & ~SelectionKey.OP_WRITE
                    );
                } catch (IOException e) {
                    close("client write failed");
                }
            }

            private void flushToRemote() {
                if (remoteKey == null || !remoteKey.isValid()) return;
                flush(remoteKey, toRemote, remote, c -> {
                    pendingToRemoteBytes -= c;
                    updateClientInterest();
                });
                updateRemoteInterest();
            }

            private void flush(
                SelectionKey key,
                ArrayDeque<ByteBuffer> q,
                java.nio.channels.SelectableChannel ch,
                LongConsumer onFlushed
            ) {
                if (!key.isValid()) return;
                SocketChannel socket = (SocketChannel) ch;
                try {
                    while (!q.isEmpty()) {
                        ByteBuffer b = q.peek();
                        int before = b.remaining();
                        socket.write(b);
                        int consumed = before - b.remaining();
                        if (consumed > 0) {
                            onFlushed.accept(consumed);
                        }
                        if (b.hasRemaining()) {
                            setInterestOps(
                                key,
                                key.interestOps() | SelectionKey.OP_WRITE
                            );
                            return;
                        }
                        q.poll();
                    }
                    setInterestOps(
                        key,
                        key.interestOps() & ~SelectionKey.OP_WRITE
                    );
                } catch (IOException e) {
                    if (key == clientKey) {
                        close("client write failed");
                    } else {
                        close("remote write failed");
                    }
                } catch (IllegalStateException e) {
                    close("session key invalid");
                } catch (RuntimeException e) {
                    close("session runtime error");
                }
            }

            private void setInterestOps(SelectionKey key, int ops) {
                if (key == null || !key.isValid()) return;
                try {
                    key.interestOps(ops);
                } catch (Exception e) {
                    close("interestOps failed");
                }
            }

            private void updateClientInterest() {
                if (clientKey == null || !clientKey.isValid()) return;
                int ops = (pendingToRemoteBytes < MAX_PENDING_BYTES
                    ? SelectionKey.OP_READ
                    : 0);
                if (!toClient.isEmpty() || (tls != null && tls.hasPendingOutput())) {
                    ops |= SelectionKey.OP_WRITE;
                }
                setInterestOps(clientKey, ops);
            }

            private void updateRemoteInterest() {
                if (remoteKey == null || !remoteKey.isValid()) return;
                int ops = (pendingToClientBytes < MAX_PENDING_BYTES
                    ? SelectionKey.OP_READ
                    : 0);
                if (!toRemote.isEmpty()) ops |= SelectionKey.OP_WRITE;
                setInterestOps(remoteKey, ops);
            }

            void close(String reason) {
                if (!closed.compareAndSet(false, true)) return;
                if (!connecting) {
                    log.fine("tcp close: " + reason);
                } else {
                    log.fine("tcp close while connecting: " + reason);
                }
                toClient.clear();
                toRemote.clear();
                pendingToClientBytes = 0;
                pendingToRemoteBytes = 0;
                try {
                    if (clientKey != null) clientKey.cancel();
                } catch (Exception ignored) {}
                try {
                    if (remoteKey != null) remoteKey.cancel();
                } catch (Exception ignored) {}
                closeClient();
                closeRemote();
            }

            private void closeClient() {
                try {
                    if (tls != null) {
                        tls.close();
                    } else if (clientKey != null) {
                        ((SocketChannel) clientKey.channel()).close();
                    }
                } catch (IOException ignored) {}
            }

            private void closeRemote() {
                try {
                    if (remote != null) remote.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
