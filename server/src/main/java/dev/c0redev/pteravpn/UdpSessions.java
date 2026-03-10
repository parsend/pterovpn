package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class UdpSessions implements AutoCloseable {

    private final Logger log = Log.logger(UdpSessions.class);
    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final Selector selector;
    private final Thread selectorThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger writerSeq = new AtomicInteger(1);
    private static final int UDP_BUFFER_SIZE = 64 * 1024;
    private static final int WRITE_RETRY_DELAY_MS = 2;
    private static final int WRITE_TIMEOUT_MS = 2_000;
    private static final long SESSION_IDLE_TIMEOUT_NANOS =
        TimeUnit.MINUTES.toNanos(5);

    UdpSessions(int channels) throws IOException {
        this.selector = Selector.open();
        this.selectorThread = new Thread(this::selectLoop, "udp-selector");
        this.selectorThread.setDaemon(true);
        this.selectorThread.start();
    }

    UdpChannelWriter createWriter(
        OutputStream out,
        Protocol.ClientOptions opts
    ) {
        int id = writerSeq.getAndIncrement();
        return new UdpChannelWriter(id, out, opts);
    }

    void removeWriter(UdpChannelWriter writer) {
        if (writer == null) return;
        writer.close();
        for (Session s : sessions.values()) {
            if (s.writer == writer) {
                closeSession(s.key, s);
            }
        }
    }

    void onFrame(UdpChannelWriter writer, int channelId, Protocol.UdpFrame f)
        throws IOException {
        Key k = new Key(
            writer.id,
            f.addrType(),
            f.srcPort(),
            f.dst().getAddress(),
            f.dstPort()
        );
        Session s = sessions.get(k);
        if (s == null) {
            Session created = createSession(k, f, writer);
            Session raced = sessions.putIfAbsent(k, created);
            if (raced != null) {
                created.close();
                s = raced;
            } else {
                s = created;
            }
        }
        try {
            s.send(f.payload());
        } catch (IOException e) {
            closeSession(k, s);
            throw e;
        }
    }

    private Session createSession(
        Key k,
        Protocol.UdpFrame f,
        UdpChannelWriter writer
    ) throws IOException {
        DatagramChannel dc = DatagramChannel.open();
        dc.configureBlocking(false);
        dc.setOption(StandardSocketOptions.SO_RCVBUF, 1 << 20);
        dc.setOption(StandardSocketOptions.SO_SNDBUF, 1 << 20);
        dc.connect(new InetSocketAddress(f.dst(), f.dstPort()));
        SelectionKey sk = dc.register(selector, SelectionKey.OP_READ);
        Session s = new Session(k, f.dst(), dc, sk, writer);
        sk.attach(s);
        selector.wakeup();
        return s;
    }

    private void selectLoop() {
        ByteBuffer buf = ByteBuffer.allocateDirect(UDP_BUFFER_SIZE);
        long lastCleanup = System.nanoTime();
        while (!closed.get()) {
            try {
                int n = selector.select(1000);
                if (n == 0) continue;
                for (SelectionKey k : selector.selectedKeys()) {
                    if (!k.isValid() || !k.isReadable()) continue;
                    Object a = k.attachment();
                    if (!(a instanceof Session s)) continue;
                    try {
                        while (true) {
                            buf.clear();
                            int r = s.dc.read(buf);
                            if (r < 0) {
                                closeSession(s.key, s);
                                break;
                            }
                            if (r == 0) break;
                            buf.flip();
                            byte[] payload = new byte[buf.remaining()];
                            buf.get(payload);
                            s.touch();
                            if (s.writer != null) {
                                s.writer.send(
                                    new Protocol.UdpFrame(
                                        s.key.addrType,
                                        s.key.srcPort,
                                        s.dst,
                                        s.key.dstPort,
                                        payload
                                    )
                                );
                            }
                        }
                    } catch (IOException e) {
                        log.warning(
                            "udp read failed for key=" +
                                s.key.srcPort +
                                ":" +
                                s.key.dstPort +
                                " - " +
                                e.getMessage()
                        );
                        closeSession(s.key, s);
                    }
                }
                selector.selectedKeys().clear();
                long now = System.nanoTime();
                if (now - lastCleanup >= TimeUnit.SECONDS.toNanos(10)) {
                    cleanupIdleSessions(now);
                    lastCleanup = now;
                }
            } catch (IOException e) {
                log.warning("udp selector error: " + e.getMessage());
            }
        }
    }

    private void cleanupIdleSessions(long nowNanos) {
        for (Session s : sessions.values()) {
            long idleNanos = nowNanos - s.lastActivityNanos();
            if (idleNanos >= SESSION_IDLE_TIMEOUT_NANOS) {
                closeSession(s.key, s);
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        for (Session s : sessions.values()) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }
        sessions.clear();
        try {
            selector.wakeup();
        } catch (Exception ignored) {}
        try {
            selector.close();
        } catch (Exception ignored) {}
    }

    private static final class Session implements AutoCloseable {

        final Key key;
        final java.net.InetAddress dst;
        final DatagramChannel dc;
        final SelectionKey sk;
        final UdpChannelWriter writer;
        private volatile long lastActivityNanos;

        Session(
            Key key,
            java.net.InetAddress dst,
            DatagramChannel dc,
            SelectionKey sk,
            UdpChannelWriter writer
        ) {
            this.key = key;
            this.dst = dst;
            this.dc = dc;
            this.sk = sk;
            this.writer = writer;
            this.lastActivityNanos = System.nanoTime();
        }

        void send(byte[] payload) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(payload);
            synchronized (this) {
                touch();
                long timeoutAt =
                    System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(WRITE_TIMEOUT_MS);
                while (bb.hasRemaining()) {
                    int w = dc.write(bb);
                    if (w > 0) continue;
                    if (System.nanoTime() >= timeoutAt) throw new IOException(
                        "udp send timeout"
                    );
                    try {
                        TimeUnit.MILLISECONDS.sleep(WRITE_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("udp send interrupted", e);
                    }
                }
            }
        }

        void touch() {
            this.lastActivityNanos = System.nanoTime();
        }

        long lastActivityNanos() {
            return lastActivityNanos;
        }

        @Override
        public void close() throws IOException {
            try {
                sk.cancel();
            } catch (Exception ignored) {}
            dc.close();
        }
    }

    public static final class UdpChannelWriter implements AutoCloseable {

        final int id;
        private final OutputStream out;
        private final Protocol.ClientOptions opts;
        private final LinkedBlockingQueue<Protocol.UdpFrame> q =
            new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Thread t;

        UdpChannelWriter(
            int id,
            OutputStream out,
            Protocol.ClientOptions opts
        ) {
            this.id = id;
            this.out = out;
            this.opts = opts;
            this.t = new Thread(this::loop, "udp-writer-" + id);
            this.t.setDaemon(true);
            this.t.start();
        }

        void send(Protocol.UdpFrame f) {
            if (closed.get()) return;
            q.offer(f);
        }

        private void loop() {
            while (!closed.get()) {
                try {
                    Protocol.UdpFrame f = q.poll(1, TimeUnit.SECONDS);
                    if (f == null) continue;
                    synchronized (out) {
                        int pad = opts != null ? opts.padS4() : 0;
                        int maxPad =
                            pad > 0 && pad <= 64 ? pad : Protocol.MAX_PAD;
                        Protocol.writeUdpFrame(out, f, maxPad);
                        out.flush();
                    }
                } catch (InterruptedException ignored) {
                } catch (IOException ignored) {
                    closed.set(true);
                }
            }
        }

        @Override
        public void close() {
            closed.set(true);
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeSession(Key key, Session s) {
        Session removed = sessions.remove(key);
        if (removed != s) return;
        try {
            removed.close();
        } catch (IOException ignored) {}
    }

    private static final class Key {

        final int writerId;
        final byte addrType;
        final int srcPort;
        final byte[] dstIp;
        final int dstPort;

        Key(
            int writerId,
            byte addrType,
            int srcPort,
            byte[] dstIp,
            int dstPort
        ) {
            this.writerId = writerId;
            this.addrType = addrType;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return (
                writerId == k.writerId &&
                addrType == k.addrType &&
                srcPort == k.srcPort &&
                dstPort == k.dstPort &&
                Arrays.equals(dstIp, k.dstIp)
            );
        }

        @Override
        public int hashCode() {
            int r = Objects.hash(writerId, addrType, srcPort, dstPort);
            r = 31 * r + Arrays.hashCode(dstIp);
            return r;
        }
    }
}
