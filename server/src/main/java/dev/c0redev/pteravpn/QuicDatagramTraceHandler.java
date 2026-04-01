package dev.c0redev.pteravpn;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;

import java.util.logging.Logger;


final class QuicDatagramTraceHandler extends ChannelInboundHandlerAdapter {
  private final Logger log;

  QuicDatagramTraceHandler(Logger log) {
    this.log = log;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    log.info("[quic-trace] udp socket active local=" + ctx.channel().localAddress());
    ctx.fireChannelActive();
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof DatagramPacket p) {
      ByteBuf c = p.content();
      int n = c.readableBytes();
      String hex = hexPreview(c, Math.min(32, n));
      log.info("[quic-trace] udp recv " + n + " B from " + p.sender() + " preview=" + hex);
    } else {
      log.info("[quic-trace] udp recv type=" + msg.getClass().getName());
    }
    ctx.fireChannelRead(msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.warning("[quic-trace] udp pipeline error: " + cause);
    ctx.fireExceptionCaught(cause);
  }

  private static String hexPreview(ByteBuf buf, int len) {
    if (len <= 0) {
      return "";
    }
    int ri = buf.readerIndex();
    StringBuilder sb = new StringBuilder(len * 3);
    for (int i = 0; i < len; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(String.format("%02x", buf.getUnsignedByte(ri + i)));
    }
    return sb.toString();
  }
}
