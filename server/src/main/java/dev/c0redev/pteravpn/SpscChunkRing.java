package dev.c0redev.pteravpn;

import java.util.concurrent.ArrayBlockingQueue;

final class SpscChunkRing {
  private final ArrayBlockingQueue<byte[]> q;

  SpscChunkRing(int capacity) {
    q = new ArrayBlockingQueue<>(Math.max(2, capacity));
  }

  boolean offer(byte[] chunk) {
    return q.offer(chunk);
  }

  void put(byte[] chunk) throws InterruptedException {
    q.put(chunk);
  }

  byte[] take() throws InterruptedException {
    return q.take();
  }
}
