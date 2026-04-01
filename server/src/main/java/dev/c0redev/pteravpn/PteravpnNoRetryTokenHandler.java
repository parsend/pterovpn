package dev.c0redev.pteravpn;

import io.netty.buffer.ByteBuf;
import io.netty.incubator.codec.quic.QuicTokenHandler;

import java.net.InetSocketAddress;
import java.util.logging.Logger;


enum PteravpnNoRetryTokenHandler implements QuicTokenHandler {
  INSTANCE;

  private static final Logger TK = Logger.getLogger("dev.c0redev.pteravpn.quic.token");

  @Override
  public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
    if (Log.quicTrace()) {
      TK.info("[quic-trace] token writeToken (no retry) dcidBytes=" + dcid.readableBytes() + " peer=" + address);
    }
    return false;
  }

  @Override
  public int validateToken(ByteBuf token, InetSocketAddress address) {
    if (Log.quicTrace()) {
      TK.info("[quic-trace] token validateToken len=" + token.readableBytes() + " peer=" + address);
    }
    return 0;
  }

  @Override
  public int maxTokenLength() {
    return 0;
  }
}
