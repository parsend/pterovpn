package dev.c0redev.pteravpn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class ProtocolHandshakeParser {

  private enum State {
    WAIT_MAGIC,
    WAIT_VERSION,
    WAIT_ROLE,
    WAIT_TOKEN_LEN,
    WAIT_TOKEN,
    WAIT_CHANNEL,
    WAIT_OPTIONS,
    DONE,
    ERROR
  }

  private final byte[] magic = Protocol.MAGIC;
  private final byte[] key;
  private final byte[] token = new byte[Protocol.MAX_TOKEN];

  private State state = State.WAIT_MAGIC;
  private byte role;
  private int tokenLen = -1;
  private int tokenRead;
  private int tokenLenHi = -1;
  private int channelId = -1;
  private int magicMatched;
  private int keyPos;
  private Protocol.HandshakeResult handshake;

  ProtocolHandshakeParser(byte[] xorKey) {
    this.key = xorKey.clone();
  }

  boolean consume(ByteBuffer data) throws IOException {
    while (data.hasRemaining()) {
      if (state == State.DONE || state == State.ERROR) {
        break;
      }
      switch (state) {
        case WAIT_MAGIC -> consumeMagic(data);
        case WAIT_VERSION -> consumeVersion(data);
        case WAIT_ROLE -> consumeRole(data);
        case WAIT_TOKEN_LEN -> consumeTokenLen(data);
        case WAIT_TOKEN -> consumeToken(data);
        case WAIT_CHANNEL -> consumeChannel(data);
        case WAIT_OPTIONS -> consumeOptions(data);
        default -> {}
      }
    }
    return state == State.DONE;
  }

  int readPos() {
    return keyPos;
  }

  Protocol.HandshakeResult result() {
    return handshake;
  }

  boolean needsClose() {
    return state == State.ERROR;
  }

  private void consumeMagic(ByteBuffer data) {
    while (data.hasRemaining() && state == State.WAIT_MAGIC) {
      int v = next(data) & 0xff;
      if (v == (magic[magicMatched] & 0xff)) {
        magicMatched++;
        if (magicMatched == Protocol.MAGIC_LEN) {
          magicMatched = 0;
          state = State.WAIT_VERSION;
          return;
        }
      } else {
        magicMatched = (v == (magic[0] & 0xff)) ? 1 : 0;
      }
    }
  }

  private void consumeVersion(ByteBuffer data) throws IOException {
    if (!data.hasRemaining()) return;
    int v = next(data) & 0xff;
    if (v != Protocol.VERSION) {
      throw new IOException("bad protocol version");
    }
    state = State.WAIT_ROLE;
  }

  private void consumeRole(ByteBuffer data) throws IOException {
    if (!data.hasRemaining()) return;
    role = (byte) (next(data) & 0xff);
    if (role != Protocol.ROLE_UDP && role != Protocol.ROLE_TCP) {
      throw new IOException("bad role");
    }
    state = State.WAIT_TOKEN_LEN;
  }

  private void consumeTokenLen(ByteBuffer data) throws IOException {
    if (tokenLen == -1) {
      if (!data.hasRemaining()) return;
      tokenLenHi = next(data) & 0xff;
      tokenLen = -2;
      return;
    }
    if (tokenLen == -2) {
      if (!data.hasRemaining()) return;
      tokenLen = (tokenLenHi << 8) | (next(data) & 0xff);
      tokenLenHi = -1;
      if (tokenLen < 0 || tokenLen > Protocol.MAX_TOKEN) {
        throw new IOException("bad token len");
      }
      tokenRead = 0;
      if (tokenLen == 0) {
        state = role == Protocol.ROLE_UDP ? State.WAIT_CHANNEL : State.WAIT_OPTIONS;
      } else {
        state = State.WAIT_TOKEN;
      }
    }
  }

  private void consumeToken(ByteBuffer data) {
    if (tokenRead >= tokenLen) {
      state = role == Protocol.ROLE_UDP ? State.WAIT_CHANNEL : State.WAIT_OPTIONS;
      return;
    }
    int need = tokenLen - tokenRead;
    int take = Math.min(need, data.remaining());
    for (int i = 0; i < take; i++) {
      token[tokenRead + i] = next(data);
    }
    tokenRead += take;
    if (tokenRead >= tokenLen) {
      state = role == Protocol.ROLE_UDP ? State.WAIT_CHANNEL : State.WAIT_OPTIONS;
    }
  }

  private void consumeChannel(ByteBuffer data) {
    if (!data.hasRemaining()) return;
    channelId = next(data) & 0xff;
    state = State.WAIT_OPTIONS;
  }

  private void consumeOptions(ByteBuffer data) {
    if (data.remaining() < 2) {
      if (looksLikeTcpConnectStart(data)) {
        finish(null);
      }
      return;
    }
    int pos = data.position();
    int hi = peek(data, pos, 0) & 0xff;
    int lo = peek(data, pos + 1, 1) & 0xff;
    int optLen = (hi << 8) | lo;
    if (optLen <= 0 || optLen > Protocol.MAX_OPTS) {
      data.position(pos);
      finish(null);
      return;
    }
    int total = 2 + optLen;
    if (data.remaining() < total) {
      return;
    }
    byte[] optBuf = new byte[optLen];
    for (int i = 0; i < optLen; i++) {
      optBuf[i] = peek(data, pos + 2 + i, 2 + i);
    }
    Optional<Protocol.ClientOptions> parsed = Protocol.ClientOptions.parse(new String(optBuf, StandardCharsets.UTF_8));
    if (parsed.isEmpty()) {
      finish(null);
      return;
    }
    consumeNow(total);
    data.position(pos + total);
    finish(parsed);
  }

  private boolean looksLikeTcpConnectStart(ByteBuffer data) {
    if (!data.hasRemaining()) {
      return false;
    }
    int at = peek(data, data.position(), 0) & 0xff;
    return at == Protocol.ADDR_V4 || at == Protocol.ADDR_V6;
  }

  private void finish(Optional<Protocol.ClientOptions> opts) {
    String tokenValue = new String(token, 0, tokenLen, StandardCharsets.UTF_8);
    Protocol.ClientOptions o = opts.orElse(null);
    handshake = new Protocol.HandshakeResult(
      new Protocol.Handshake(role, channelId, tokenValue),
      o == null ? Optional.empty() : Optional.of(o)
    );
    state = State.DONE;
  }

  private byte next(ByteBuffer data) {
    return (byte) (data.get() ^ key[keyPos++ % key.length]);
  }

  private byte peek(ByteBuffer data, int idx, int keyOffset) {
    return (byte) (data.get(idx) ^ key[(keyPos + keyOffset) % key.length]);
  }

  private void consumeNow(int n) {
    keyPos += n;
  }
}
