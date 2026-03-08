package dev.c0redev.pteravpn;

import java.util.concurrent.ConcurrentLinkedQueue;

final class BufferPool {

  static final int SMALL_CAP = 1500;
  static final int LARGE_CAP = 64 * 1024;

  private static final ConcurrentLinkedQueue<byte[]> SMALL_POOL = new ConcurrentLinkedQueue<>();
  private static final ConcurrentLinkedQueue<byte[]> LARGE_POOL = new ConcurrentLinkedQueue<>();

  private BufferPool() {}

  // owner gets one of fixed size buffers and must return it through release after use
  static byte[] borrow(int minCapacity) {
    if (minCapacity <= SMALL_CAP) {
      byte[] buf = SMALL_POOL.poll();
      return buf != null ? buf : new byte[SMALL_CAP];
    }
    byte[] buf = LARGE_POOL.poll();
    return buf != null ? buf : new byte[LARGE_CAP];
  }

  // return is final stage of ownership, do not reuse buffer before this call
  static void release(byte[] buf) {
    if (buf == null) return;
    if (buf.length == SMALL_CAP) {
      SMALL_POOL.offer(buf);
      return;
    }
    if (buf.length == LARGE_CAP) {
      LARGE_POOL.offer(buf);
    }
  }
}
