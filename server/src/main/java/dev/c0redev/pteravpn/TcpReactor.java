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
        int idx = Math.floorMod(rr.getAndIncrement(), reactors.length);
        reactors[idx].register(
            client,
            connect,
            xor,
            initialClientData,
            initialClientDataDecrypted
        );
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
            byte[] initialClientData,
            boolean initialClientDataDecrypted
        ) throws IOException {
            if (closed.get()) {
                throw new IOException("reactor closed");
            }
            pending.add(
                new Registration(
                    client,
                    connect,
                    xor,
                    initialClientData,
                    initialClientDataDecrypted
                )
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
                closeChannel(reg.client());
            }
        }

        private void processSelectedKeys() {
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;

                if (!(key.attachment() instanceof Session session)) continue;
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

        private record Registration(
            SocketChannel client,
            Protocol.TcpConnect connect,
            XorStream xor,
            byte[] initialClientData,
            boolean initialClientDataDecrypted
        ) {}

        private static final class Session {

            private static final long CONNECT_TIMEOUT_NANOS =
                TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
            private static final Logger log = Log.logger(Session.class);

            private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            private final ArrayDeque<ByteBuffer> toClient = new ArrayDeque<>();
            private final ArrayDeque<ByteBuffer> toRemote = new ArrayDeque<>();
            private final XorStream xor;
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
                byte[] initialClientData,
                boolean initialClientDataDecrypted
            ) {
                this.xor = xor;
                if (initialClientData != null && initialClientData.length > 0) {
                    if (!initialClientDataDecrypted) {
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
                    SocketChannel client = (SocketChannel) clientKey.channel();
                    int n = client.read(buffer.clear());
                    if (n == -1) {
                        close("client closed");
                        return;
                    }
                    if (n == 0) return;

                    buffer.flip();
                    byte[] data = new byte[n];
                    buffer.get(data);
                    xor.decode(data, 0, n);
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
                    xor.encode(data, 0, n);
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
                flush(clientKey, toClient, clientKey.channel(), c -> {
                    pendingToClientBytes -= c;
                    updateRemoteInterest();
                });
                updateClientInterest();
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
                        socket.write(b);
                        if (b.hasRemaining()) {
                            setInterestOps(
                                key,
                                key.interestOps() | SelectionKey.OP_WRITE
                            );
                            return;
                        }
                        q.poll();
                        onFlushed.accept((long) b.capacity());
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
                if (!toClient.isEmpty()) ops |= SelectionKey.OP_WRITE;
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
                    if (clientKey != null) {
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
