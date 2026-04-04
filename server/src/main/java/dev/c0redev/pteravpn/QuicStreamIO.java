package dev.c0redev.pteravpn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

final class QuicStreamIO {
  private static final byte[] EOF = new byte[0];

  private final SpscChunkRing ring;
  private final Runnable onSpace;
  private volatile boolean eofQueued;

  QuicStreamIO(int ringCapacity, Runnable onSpace) {
    this.ring = new SpscChunkRing(ringCapacity);
    this.onSpace = onSpace != null ? onSpace : () -> {};
  }

  boolean feed(byte[] bytes) {
    return ring.offer(bytes);
  }

  void endInput() {
    if (eofQueued) {
      return;
    }
    eofQueued = true;
    ForkJoinPool.commonPool().execute(() -> {
      try {
        ring.put(EOF);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  InputStream input() {
    return new InputStream() {
      private byte[] cur;
      private int off;

      @Override
      public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : (b[0] & 0xff);
      }

      @Override
      public int read(byte[] b, int o, int l) throws IOException {
        while (cur == null || off >= cur.length) {
          byte[] next;
          try {
            next = ring.take();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
          off = 0;
          if (next == EOF) {
            onSpace.run();
            return -1;
          }
          cur = next;
          onSpace.run();
        }
        int n = Math.min(l, cur.length - off);
        System.arraycopy(cur, off, b, o, n);
        off += n;
        return n;
      }
    };
  }

  OutputStream output(Consumer<byte[]> sink) {
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        sink.accept(new byte[]{(byte) b});
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        sink.accept(Arrays.copyOfRange(b, off, off + len));
      }
    };
  }
}
