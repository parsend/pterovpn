package dev.c0redev.pteravpn;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class QuicServer implements AutoCloseable {
  private static final Logger log = Log.logger(QuicServer.class);

  private static volatile byte[] advertisedQuicLeafPin;

  static byte[] getAdvertisedQuicLeafPin() {
    return advertisedQuicLeafPin;
  }

  private final Config cfg;
  private final UdpSessions udp;
  private final ExecutorService streamPool;
  private final AtomicInteger handshakes = new AtomicInteger();
  private final ScheduledExecutorService handshakeTimers =
      Executors.newSingleThreadScheduledExecutor();
  
  private final NioEventLoopGroup group = new NioEventLoopGroup(0);
  private Channel channel;
  
  private final QuicParentHandler quicParentHandler = new QuicParentHandler();
  private final QuicStreamChannelInit quicStreamChannelInit = new QuicStreamChannelInit();

  QuicServer(Config cfg, UdpSessions udp, ExecutorService streamPool) {
    this.cfg = cfg;
    this.udp = udp;
    this.streamPool = streamPool;
  }

  void start() throws Exception {
    QuicSslContextBuilder sslBuilder;
    File certFile = new File(cfg.quicCertPath());
    File keyFile = new File(cfg.quicKeyPath());
    X509Certificate quicLeaf;
    if (certFile.isFile() && keyFile.isFile()) {
      sslBuilder = QuicSslContextBuilder.forServer(keyFile, null, certFile);
      log.info("QUIC TLS: PEM " + certFile.getPath() + " + " + keyFile.getPath());
      quicLeaf = QuicTlsUtil.firstCertFromPem(certFile);
    } else {
      QuicEphemeralCert.Material mat = QuicEphemeralCert.generate(cfg.publicHost());
      quicLeaf = mat.certificate;
      boolean saved = QuicEphemeralCert.tryPersist(mat, certFile, keyFile);
      sslBuilder = QuicSslContextBuilder.forServer(mat.privateKey, null, mat.certificate);
      if (saved) {
        log.info("QUIC TLS: generated EC self-signed, PEM saved for next restarts");
      } else {
        log.warning("QUIC TLS: generated EC self-signed in memory only (restart will get a new cert)");
      }
    }
    log.info("QUIC leaf SHA256 for client quicCertPinSHA256: " + QuicTlsUtil.sha256HexDer(quicLeaf.getEncoded()));
    advertisedQuicLeafPin = QuicTlsUtil.sha256Raw(quicLeaf.getEncoded());
    QuicSslContext sslContext = sslBuilder.applicationProtocols(cfg.quicAlpn()).build();
    int maxBi = Math.max(4, cfg.quicMaxStreams());
    if (cfg.quicMaxStreams() <= 0) {
      
      maxBi = 4096;
    }
    long streamWin = Math.min(16_000_000L, 1_200_000L + (long) maxBi * 80_000L);
    long connWin = Math.min(120_000_000L, streamWin * maxBi);
    long idleMs = cfg.quicIdleTimeoutMs();
    if (idleMs <= 0) idleMs = TimeUnit.HOURS.toMillis(24);
    ChannelHandler codec = new QuicServerCodecBuilder()
        .sslContext(sslContext)
        .maxIdleTimeout(idleMs, TimeUnit.MILLISECONDS)
        .activeMigration(false)
        .initialMaxStreamsBidirectional(maxBi)
        .initialMaxStreamsUnidirectional(0)
        .initialMaxData(connWin)
        .initialMaxStreamDataBidirectionalLocal(streamWin)
        .initialMaxStreamDataBidirectionalRemote(streamWin)
        .tokenHandler(PteravpnNoRetryTokenHandler.INSTANCE)
        .handler(quicParentHandler)
        .streamHandler(quicStreamChannelInit).build();

    Bootstrap bs = new Bootstrap().group(group)
        .channel(NioDatagramChannel.class)
        .option(ChannelOption.SO_REUSEADDR, true);
    if (cfg.quicTraceLog()) {
      bs.handler(new ChannelInitializer<NioDatagramChannel>() {
        @Override
        protected void initChannel(NioDatagramChannel ch) {
          ch.pipeline().addLast("quic-udp-trace", new QuicDatagramTraceHandler(log));
          ch.pipeline().addLast("quic-pipeline-log", new LoggingHandler("quic-udp", LogLevel.DEBUG));
          ch.pipeline().addLast(codec);
        }
      });
    } else {
      bs.handler(codec);
    }
    channel = bs.bind(new InetSocketAddress(cfg.quicListenPort())).sync().channel();
    log.info("QUIC listening on : " + cfg.quicListenPort());
  }

  @Override
  public void close() {
    advertisedQuicLeafPin = null;
    try {
      if (channel != null) channel.close().syncUninterruptibly();
    } catch (Exception ignored) {}
    try {
      group.shutdownGracefully(0, 8, TimeUnit.SECONDS).syncUninterruptibly();
    } catch (Exception e) {
      group.shutdownGracefully();
    }
    try {
      handshakeTimers.shutdownNow();
    } catch (Exception ignored) {}
  }

  
  @Sharable
  private final class QuicParentHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
      if (cfg.quicTraceLog()) {
        log.fine("[quic-trace] parent REGISTERED " + ctx.channel());
      }
      ctx.fireChannelRegistered();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      log.info("QUIC conn from " + ctx.channel().remoteAddress());
      if (cfg.quicTraceLog()) {
        log.info("[quic-trace] parent ACTIVE local=" + ctx.channel().localAddress()
          + " remote=" + ctx.channel().remoteAddress() + " ch=" + ctx.channel());
      }
      ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      if (cfg.quicTraceLog()) {
        log.info("[quic-trace] parent INACTIVE remote=" + ctx.channel().remoteAddress());
      }
      ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
      if (cfg.quicTraceLog()) {
        log.info("[quic-trace] parent userEvent " + evt.getClass().getName() + " " + evt);
      }
      ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.warning("QUIC conn error: " + cause);
      ctx.close();
    }
  }

  @Sharable
  private final class QuicStreamChannelInit extends ChannelInitializer<QuicStreamChannel> {
    @Override
    protected void initChannel(QuicStreamChannel ch) {
      if (cfg.quicTraceLog()) {
        log.info("[quic-trace] stream initChannel id=" + ch.id() + " remote=" + ch.remoteAddress());
      }
      ch.pipeline().addLast(new QuicStreamInboundHandler(ch));
    }
  }

  private final class QuicStreamInboundHandler extends ChannelInboundHandlerAdapter {
    private final QuicStreamChannel stream;
    private final QuicStreamIO io = new QuicStreamIO();

    QuicStreamInboundHandler(QuicStreamChannel stream) {
      this.stream = stream;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (msg instanceof ByteBuf b) {
        byte[] data = new byte[b.readableBytes()];
        b.readBytes(data);
        io.feed(data);
        b.release();
        return;
      }
      ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      io.endInput();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      if (cfg.quicTraceLog()) {
        log.info("[quic-trace] stream channelActive id=" + ctx.channel().id()
          + " remote=" + stream.remoteAddress() + " handshakesPending≈" + handshakes.get());
      }
      final boolean countHandshake = cfg.quicMaxHandshakes() > 0;
      if (countHandshake && handshakes.incrementAndGet() > cfg.quicMaxHandshakes()) {
        handshakes.decrementAndGet();
        log.warning("[quic-trace] quicMaxHandshakes exceeded, closing stream");
        stream.close();
        return;
      }
      try {
        Future<?> f = streamPool.submit(() -> {
          boolean keepStreamOpen = false;
          boolean released = false;
          try {
            InputStream in = io.input();
            OutputStream out = io.output(bytes -> stream.writeAndFlush(Unpooled.wrappedBuffer(bytes)));
            log.info("QUIC (UDP) stream from " + stream.remoteAddress());
            Protocol.HandshakeResult hr = Protocol.readHandshake(in);
            Protocol.Handshake hs = hr.handshake();
            if (cfg.quicTraceLog()) {
              log.info("[quic-trace] pteravpn handshake read role=" + hs.role() + " ch=" + hs.channelId());
            }
            if (!MessageDigest.isEqual(cfg.token().getBytes(StandardCharsets.UTF_8), hs.token().getBytes(StandardCharsets.UTF_8))) {
              int legacyIpv6 = Ipv6Detect.hasIPv6() ? 1 : 0;
              int transportMask = 0;
              if (cfg.tcpEnabled()) transportMask |= Protocol.TRANSPORT_TCP;
              if (cfg.quicEnabled()) transportMask |= Protocol.TRANSPORT_QUIC;
              int featureBits = legacyIpv6 == 1 ? Protocol.FEAT_IPV6 : 0;
              int tcpPortHint = cfg.listenPorts().isEmpty() ? 0 : cfg.listenPorts().get(0);
              Protocol.writeServerHelloCaps(out, new Protocol.ServerHelloCaps(
                  Protocol.CAPS_VERSION, legacyIpv6, transportMask, featureBits,
                  cfg.quicEnabled() ? cfg.quicListenPort() : 0, tcpPortHint, 0, new byte[0],
                  QuicServer.getAdvertisedQuicLeafPin()));
              return;
            }
            if (hs.role() == Protocol.ROLE_UDP) {
              keepStreamOpen = true;
            }
           
            if (countHandshake && !released) {
              handshakes.decrementAndGet();
              released = true;
            }
            var session = new SessionHandler(cfg, udp, String.valueOf(stream.remoteAddress()), stream::close);
            session.handle(hs, hr, in, out, (connect, rest) -> handleTcpOverQuic(connect, rest, out), null);
          } catch (Exception e) {
            log.warning("QUIC stream session failed: " + e);
            if (cfg.quicTraceLog()) {
              log.log(java.util.logging.Level.FINE, "[quic-trace] stream session stack", e);
            }
          } finally {
            if (countHandshake && !released) {
              handshakes.decrementAndGet();
            }
            if (!keepStreamOpen) {
              try {
                stream.close();
              } catch (Exception ignored) {}
            }
          }
        });
        if (countHandshake || cfg.quicIdleTimeoutMs() >= 0) {
          handshakeTimers.schedule(
              () -> f.cancel(true),
              15_000,
              TimeUnit.MILLISECONDS
          );
        }
      } catch (RejectedExecutionException e) {
        if (countHandshake) handshakes.decrementAndGet();
        try {
          stream.close();
        } catch (Exception ignored) {}
      }
    }
  }

  private void handleTcpOverQuic(Protocol.TcpConnect c, InputStream in, OutputStream out) throws IOException {
    try (Socket remote = new Socket(c.ip(), c.port())) {
      remote.setTcpNoDelay(true);
      var remoteIn = remote.getInputStream();
      var remoteOut = remote.getOutputStream();
      Thread t1 = new Thread(() -> copy(in, remoteOut), "quic-tcp-up");
      Thread t2 = new Thread(() -> copy(remoteIn, out), "quic-tcp-down");
      t1.start();
      t2.start();
      t1.join();
      t2.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void copy(InputStream in, OutputStream out) {
    byte[] buf = new byte[32 * 1024];
    try {
      while (true) {
        int n = in.read(buf);
        if (n < 0) return;
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException ignored) {}
  }
}
