package dev.c0redev.pteravpn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

final class TlsTunnel {

    enum HandshakeStep { NEED_READ, NEED_WRITE, DONE }

    private static final int MAX_APP_BUFFER = 256 * 1024;
    private final SocketChannel channel;
    private final SSLEngine engine;
    private final ByteBuffer netIn;
    private final ByteBuffer netOut;
    private ByteBuffer appIn;
    private ByteBuffer pendingEncrypted;
    private boolean handshakeStarted;

    TlsTunnel(SocketChannel channel, SSLEngine engine) {
        this.channel = channel;
        this.engine = engine;
        var session = engine.getSession();
        this.netIn = ByteBuffer.allocate(session.getPacketBufferSize());
        this.netOut = ByteBuffer.allocate(session.getPacketBufferSize());
        this.appIn = ByteBuffer.allocate(session.getApplicationBufferSize());
        this.appIn.flip();
    }

    void setInitialEncrypted(byte[] buf) {
        if (buf == null || buf.length == 0) return;
        netIn.clear();
        netIn.put(buf);
        netIn.flip();
    }

    boolean isHandshakeComplete() {
        var hs = engine.getHandshakeStatus();
        return hs == SSLEngineResult.HandshakeStatus.FINISHED
            || hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    HandshakeStep doHandshakeNonBlocking() throws IOException {
        if (!handshakeStarted) {
            engine.beginHandshake();
            handshakeStarted = true;
        }
        if (isHandshakeComplete()) return HandshakeStep.DONE;
        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        switch (hs) {
            case NEED_UNWRAP -> {
                if (!netIn.hasRemaining()) {
                    netIn.clear();
                    int n = channel.read(netIn);
                    if (n < 0) throw new SSLException("mtls: channel closed during handshake");
                    if (n == 0) return HandshakeStep.NEED_READ;
                    netIn.flip();
                }
                appIn.clear();
                SSLEngineResult r = engine.unwrap(netIn, appIn);
                hs = r.getHandshakeStatus();
                switch (r.getStatus()) {
                    case OK -> {}
                    case BUFFER_OVERFLOW -> throw new SSLException("mtls: unwrap handshake BUFFER_OVERFLOW");
                    case BUFFER_UNDERFLOW -> {
                        compact(netIn);
                        int n = channel.read(netIn);
                        if (n < 0) throw new SSLException("mtls: channel closed during handshake");
                        if (n == 0) return HandshakeStep.NEED_READ;
                        netIn.flip();
                    }
                    default -> throw new SSLException("mtls: unwrap handshake " + r.getStatus());
                }
                return doHandshakeNonBlocking();
            }
            case NEED_WRAP -> {
                while (netOut.hasRemaining()) {
                    if (channel.write(netOut) == 0) return HandshakeStep.NEED_WRITE;
                }
                netOut.clear();
                SSLEngineResult r = engine.wrap(ByteBuffer.allocate(0), netOut);
                if (r.getStatus() != SSLEngineResult.Status.OK) {
                    throw new SSLException("mtls: wrap handshake " + r.getStatus());
                }
                netOut.flip();
                while (netOut.hasRemaining()) {
                    if (channel.write(netOut) == 0) return HandshakeStep.NEED_WRITE;
                }
                return doHandshakeNonBlocking();
            }
            case NEED_TASK -> {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) task.run();
                return doHandshakeNonBlocking();
            }
            case FINISHED, NOT_HANDSHAKING -> { return HandshakeStep.DONE; }
            default -> throw new SSLException("mtls: handshake " + hs);
        }
    }

    int read(ByteBuffer dst) throws IOException {
        if (appIn.hasRemaining()) {
            return drainApp(dst);
        }
        appIn.clear();
        while (true) {
            if (!netIn.hasRemaining()) {
                netIn.clear();
                int n = channel.read(netIn);
                if (n < 0) return -1;
                if (n == 0) {
                    netIn.flip();
                    return 0;
                }
                netIn.flip();
            }
            SSLEngineResult r = engine.unwrap(netIn, appIn);
            switch (r.getStatus()) {
                case OK -> {
                    appIn.flip();
                    if (appIn.hasRemaining()) {
                        return drainApp(dst);
                    }
                    appIn.clear();
                }
                case BUFFER_UNDERFLOW -> {
                    appIn.flip();
                    if (appIn.hasRemaining()) {
                        return drainApp(dst);
                    }
                    appIn.clear();
                    compact(netIn);
                    int n = channel.read(netIn);
                    if (n < 0) return -1;
                    if (n == 0) {
                        netIn.flip();
                        return 0;
                    }
                    netIn.flip();
                }
                case BUFFER_OVERFLOW -> {
                    expandAppInForWrite();
                }
                case CLOSED -> {
                    return -1;
                }
                default -> throw new SSLException(
                    "mtls: unwrap " + r.getStatus()
                );
            }
        }
    }

    private int drainApp(ByteBuffer dst) {
        int n = Math.min(dst.remaining(), appIn.remaining());
        int limit = appIn.limit();
        appIn.limit(appIn.position() + n);
        dst.put(appIn);
        appIn.limit(limit);
        if (!appIn.hasRemaining()) {
            appIn.clear();
            appIn.flip();
        }
        return n;
    }

    private void expandAppInForWrite() {
        int sessionSize = engine.getSession().getApplicationBufferSize();
        int newCap = Math.max(appIn.capacity() * 2, sessionSize * 2);
        if (newCap > MAX_APP_BUFFER) {
            throw new IllegalStateException(
                "mtls: app buffer overflow cap " + MAX_APP_BUFFER
            );
        }
        ByteBuffer bigger = ByteBuffer.allocate(newCap);
        appIn.flip();
        bigger.put(appIn);
        appIn = bigger;
    }

    boolean hasPendingOutput() {
        return pendingEncrypted != null && pendingEncrypted.hasRemaining();
    }

    boolean flushPending() throws IOException {
        if (pendingEncrypted == null || !pendingEncrypted.hasRemaining()) {
            return true;
        }
        channel.write(pendingEncrypted);
        if (!pendingEncrypted.hasRemaining()) {
            pendingEncrypted = null;
            return true;
        }
        return false;
    }

    int write(ByteBuffer src) throws IOException {
        while (pendingEncrypted != null && pendingEncrypted.hasRemaining()) {
            int w = channel.write(pendingEncrypted);
            if (w == 0) {
                return 0;
            }
            if (!pendingEncrypted.hasRemaining()) {
                pendingEncrypted = null;
            }
        }
        if (!src.hasRemaining()) {
            return 0;
        }
        netOut.clear();
        SSLEngineResult r = engine.wrap(src, netOut);
        switch (r.getStatus()) {
            case OK -> {
            }
            case CLOSED -> {
                return -1;
            }
            default -> throw new SSLException("mtls: wrap " + r.getStatus());
        }
        netOut.flip();
        while (netOut.hasRemaining()) {
            int w = channel.write(netOut);
            if (w == 0) {
                if (pendingEncrypted == null) {
                    pendingEncrypted = ByteBuffer.allocate(netOut.capacity());
                }
                pendingEncrypted.clear();
                pendingEncrypted.put(netOut);
                pendingEncrypted.flip();
                break;
            }
        }
        return r.bytesConsumed();
    }

    SocketChannel channel() {
        return channel;
    }

    int available() {
        return appIn.remaining();
    }

    void close() {
        try {
            engine.closeOutbound();
        } catch (Exception ignored) {}
        try {
            channel.close();
        } catch (IOException ignored) {}
    }

    private static void compact(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            buf.clear();
            return;
        }
        buf.compact();
    }
}
