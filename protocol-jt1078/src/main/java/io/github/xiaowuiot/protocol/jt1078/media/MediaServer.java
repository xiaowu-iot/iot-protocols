package io.github.xiaowuiot.protocol.jt1078.media;

import io.github.xiaowuiot.protocol.jt1078.FrameAssembler;
import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;
import io.github.xiaowuiot.protocol.jt1078.Jt1078PacketDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1078 媒体服务：收终端推的流（TCP），按 HTTP-FLV 分发给浏览器。
 * <pre>
 * 终端 ──TCP 1078 码流──▶ 实时流端口 ─┐                         ┌─▶ 浏览器 GET /live/13800138000_1.flv?token=…
 * 终端 ──TCP 1078 码流──▶ 回放流端口 ─┴─▶ 帧重组 ─▶ FLV ─▶ 流 ─┤
 *                                                           └─▶ 浏览器 GET /live/13800138000_1_pb.flv?token=…
 * </pre>
 * 实时和回放分两个端口：同一个通道可能一边看实时、一边看回放，码流里分不出是哪一路。
 * <p>
 * 和 808 信令服务分开：信令只是几条小消息，视频是持续的大流量，两者分开部署、各自扩容。
 */
public class MediaServer {

    private static final Logger log = LoggerFactory.getLogger(MediaServer.class);
    private static final Pattern PATH = Pattern.compile(".*/live/([0-9A-Za-z_]+)\\.flv$");

    private final StreamHub hub;
    private final String bindAddress;
    private final int livePort;
    private final int playbackPort;
    private final int httpPort;
    private final int simLength;
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private final AtomicLong packets = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private Channel liveChannel;
    private Channel playbackChannel;
    private Channel httpChannel;

    /**
     * @param simLength SIM 卡号字节数：6、10，0 表示自动识别
     */
    public MediaServer(StreamHub hub, String bindAddress, int livePort, int playbackPort, int httpPort, int simLength) {
        this.hub = hub;
        this.bindAddress = bindAddress;
        this.livePort = livePort;
        this.playbackPort = playbackPort;
        this.httpPort = httpPort;
        this.simLength = simLength;
    }

    public void start() throws InterruptedException {
        liveChannel = ingest(livePort, false);
        if (playbackPort > 0) {
            playbackChannel = ingest(playbackPort, true);
        }
        httpChannel = new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(256 * 1024, 2 * 1024 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(8192), new HttpFlvHandler());
                    }
                }).bind(address(httpPort)).sync().channel();
        log.info("1078 媒体服务已启动：实时流 {}，回放流 {}，HTTP-FLV {}，监听 {}", livePort, playbackPort, httpPort,
                bindAddress == null ? "*" : bindAddress);
    }

    private Channel ingest(int port, boolean playback) throws InterruptedException {
        return new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_RCVBUF, 256 * 1024)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS))
                                .addLast(new Jt1078PacketDecoder(simLength))
                                .addLast(new IngestHandler(playback));
                    }
                }).bind(address(port)).sync().channel();
    }

    private InetSocketAddress address(int port) {
        return bindAddress == null || bindAddress.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bindAddress, port);
    }

    public void stop() {
        for (Channel c : new Channel[]{liveChannel, playbackChannel, httpChannel}) {
            if (c != null) {
                c.close();
            }
        }
        hub.shutdown();
        worker.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }

    public StreamHub hub() {
        return hub;
    }

    public long packets() {
        return packets.get();
    }

    public long bytesOut() {
        return bytesOut.get();
    }

    // ==================== 收流 ====================

    /**
     * 一条推流连接。一条连接上可能有多个通道（有的终端一条连接推所有通道），按通道分别重组
     */
    private final class IngestHandler extends SimpleChannelInboundHandler<Jt1078Packet> {
        private final boolean playback;
        private final Map<String, FrameAssembler> assemblers = new HashMap<>();
        private final Map<String, MediaStream> streams = new HashMap<>();
        private final java.util.Set<String> rejected = new java.util.HashSet<>();

        IngestHandler(boolean playback) {
            this.playback = playback;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Jt1078Packet p) {
            packets.incrementAndGet();
            String key = p.streamKey() + (playback ? "_pb" : "");
            MediaStream s = streams.get(key);
            if (s == null) {
                if (rejected.contains(key)) {
                    return;
                }
                if (!hub.authorizer().allowPublish(key)) {
                    if (streams.isEmpty()) {
                        log.warn("{} 推流 {} 没有经过平台请求，断开", ctx.channel().remoteAddress(), key);
                        ctx.close();
                    } else {
                        // 同一条连接上别的通道是平台请求过的：只丢这个通道的数据，不影响正在看的通道
                        log.warn("{} 推流 {} 没有经过平台请求，丢弃这个通道的数据", ctx.channel().remoteAddress(), key);
                        rejected.add(key);
                    }
                    return;
                }
                Channel ch = ctx.channel();
                s = hub.publish(key, ch, ch::close);
                streams.put(key, s);
                assemblers.put(key, new FrameAssembler());
                log.info("{} 推流 {}（{}）", ctx.channel().remoteAddress(), key, Jt1078Packet.payloadName(p.payloadType));
            } else if (s.isClosed()) {
                // 没人看了、平台已经下发 0x9102，终端还在推：断开连接，终端就停了
                log.info("{} 流 {} 已关闭，断开推流连接", ctx.channel().remoteAddress(), key);
                ctx.close();
                return;
            }
            FrameAssembler.Frame f = assemblers.get(key).add(p);
            if (f != null) {
                s.onFrame(f);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel ch = ctx.channel();
            streams.keySet().forEach(k -> hub.unpublish(k, ch));
            streams.clear();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                log.info("{} 推流连接 30 秒没有数据，断开", ctx.channel().remoteAddress());
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("{} 推流连接异常：{}", ctx.channel().remoteAddress(), cause.toString());
            ctx.close();
        }
    }

    // ==================== HTTP-FLV ====================

    private final class HttpFlvHandler extends SimpleChannelInboundHandler<FullHttpRequest> implements MediaStream.Subscriber {
        private String key;
        private Channel channel;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            channel = ctx.channel();
            QueryStringDecoder qs = new QueryStringDecoder(req.uri());
            Matcher m = PATH.matcher(qs.path());
            if (!m.matches()) {
                respond(ctx, HttpResponseStatus.NOT_FOUND, "not found");
                return;
            }
            String k = m.group(1);
            List<String> token = qs.parameters().get("token");
            if (!hub.authorizer().allowPlay(k, token == null || token.isEmpty() ? "" : token.get(0))) {
                respond(ctx, HttpResponseStatus.FORBIDDEN, "forbidden");
                return;
            }
            HttpResponse resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "video/x-flv");
            resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            HttpUtil.setTransferEncodingChunked(resp, true);
            ctx.writeAndFlush(resp);
            key = k;
            hub.subscribe(k, this);
        }

        private void respond(ChannelHandlerContext ctx, HttpResponseStatus status, String text) {
            DefaultFullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                    ctx.alloc().buffer().writeBytes(text.getBytes(StandardCharsets.UTF_8)));
            r.headers().set(HttpHeaderNames.CONTENT_LENGTH, r.content().readableBytes());
            r.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            ctx.writeAndFlush(r).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public boolean write(ByteBuf data) {
            Channel c = channel;
            if (c == null || !c.isActive()) {
                data.release();
                return false;
            }
            if (!c.isWritable()) {
                // 写缓冲超过高水位：这个观看者网络跟不上，丢掉这一帧，等下一个关键帧
                data.release();
                return false;
            }
            bytesOut.addAndGet(data.readableBytes());
            c.writeAndFlush(new DefaultHttpContent(data), c.voidPromise());
            return true;
        }

        @Override
        public void close() {
            Channel c = channel;
            if (c != null) {
                c.close();
            }
        }

        @Override
        public String id() {
            return String.valueOf(channel == null ? "?" : channel.remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (key != null) {
                hub.unsubscribe(key, this);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
