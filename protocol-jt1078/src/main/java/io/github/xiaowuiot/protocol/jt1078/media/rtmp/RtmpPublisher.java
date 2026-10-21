package io.github.xiaowuiot.protocol.jt1078.media.rtmp;

import io.github.xiaowuiot.protocol.jt1078.media.MediaStream;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 把一路流用 RTMP 推给 ZLMediaKit、SRS、nginx-rtmp 或 CDN，由它们再转成 HLS、WebRTC、RTSP 分发。
 * <p>
 * 本身是这路流的一个<b>旁听</b>观看者（{@link MediaStream.Subscriber#passive()}）：不算观看人数，平台里没人看了照样按时关流；
 * 发布成功后才加入，加入时马上拿到序列头和整组画面（GOP），对端立刻能出图。
 * <p>
 * 流程：TCP 连接 → 简单握手 → connect → releaseStream / FCPublish / createStream → publish →
 * 收到 NetStream.Publish.Start 后开始发音视频。音视频消息的数据就是 FLV tag 的数据部分，时间戳沿用 FLV tag 的时间戳。
 * <p>
 * 对端断开（重启、网络抖动）时按 2、4、8……最长 30 秒的间隔重连；流关闭时停止，不再重连。
 */
public class RtmpPublisher implements MediaStream.Subscriber {

    private static final Logger log = LoggerFactory.getLogger(RtmpPublisher.class);
    private static final int OUT_CHUNK = 4096;
    private static final int CSID_CONTROL = 2;
    private static final int CSID_COMMAND = 3;
    private static final int CSID_AUDIO = 4;
    private static final int CSID_STREAM = 5;
    private static final int CSID_VIDEO = 6;

    /** 状态，给管理页面看 */
    public enum State { CONNECTING, PUBLISHING, RETRYING, STOPPED }

    private final MediaStream stream;
    private final String url;
    private final EventLoopGroup group;
    private final String host;
    private final int port;
    private final String app;
    private final String tcUrl;
    private final String streamName;

    private volatile State state = State.CONNECTING;
    private volatile String lastError = "";
    private volatile Channel channel;
    private volatile int streamId;
    private volatile boolean stopped;
    private volatile long bytesOut;
    private volatile long publishedAt;
    private int retryDelay = 2;
    private int retries;

    /**
     * @param url rtmp://主机[:端口]/应用/流名[?参数]，参数会带在流名后面（SRS、ZLMediaKit 的推流鉴权都这么传）
     */
    public RtmpPublisher(MediaStream stream, String url, EventLoopGroup group) {
        this.stream = stream;
        this.url = url;
        this.group = group;
        URI u = URI.create(url);
        if (!"rtmp".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) {
            throw new IllegalArgumentException("转推地址要以 rtmp:// 开头：" + url);
        }
        host = u.getHost();
        port = u.getPort() > 0 ? u.getPort() : 1935;
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        int slash = path.lastIndexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw new IllegalArgumentException("转推地址要写成 rtmp://主机/应用/流名：" + url);
        }
        app = path.substring(1, slash);
        streamName = path.substring(slash + 1) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        tcUrl = "rtmp://" + host + (u.getPort() > 0 ? ":" + port : "") + "/" + app;
    }

    public void start() {
        stream.onClose(this::close);
        connect();
    }

    public boolean isStopped() {
        return stopped;
    }

    private void connect() {
        if (stopped) {
            return;
        }
        state = retries == 0 ? State.CONNECTING : State.RETRYING;
        Bootstrap b = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(512 * 1024, 4 * 1024 * 1024))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Session s = new Session();
                        ch.pipeline().addLast(new ReadTimeoutHandler(60))
                                .addLast("decoder", new RtmpDecoder(s))
                                .addLast(s);
                    }
                });
        b.connect(host, port).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                fail("连接 " + host + ":" + port + " 失败：" + f.cause().getMessage());
                return;
            }
            Channel ch = f.channel();
            channel = ch;
            if (stopped) {
                ch.close();
                return;
            }
            // C0 + C1：版本 3，时间 4 字节，0 4 字节，随机 1528 字节
            ByteBuf c01 = ch.alloc().buffer(1 + RtmpDecoder.HANDSHAKE_SIZE);
            c01.writeByte(3);
            c01.writeInt(0);
            c01.writeInt(0);
            byte[] random = new byte[RtmpDecoder.HANDSHAKE_SIZE - 8];
            ThreadLocalRandom.current().nextBytes(random);
            c01.writeBytes(random);
            ch.writeAndFlush(c01);
            ch.closeFuture().addListener(cf -> onClosed(ch));
        });
    }

    private void fail(String reason) {
        lastError = reason;
        scheduleRetry();
    }

    private void onClosed(Channel ch) {
        if (channel != ch) {
            return;
        }
        boolean wasPublishing = state == State.PUBLISHING;
        stream.removeSubscriber(this);
        if (stopped) {
            state = State.STOPPED;
            return;
        }
        if (wasPublishing) {
            log.warn("{} 转推连接断开，准备重连", url);
            lastError = "连接断开";
            retryDelay = 2;
        }
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (stopped) {
            state = State.STOPPED;
            return;
        }
        state = State.RETRYING;
        retries++;
        int delay = retryDelay;
        retryDelay = Math.min(30, retryDelay * 2);
        if (retries == 1 || retries % 10 == 0) {
            log.warn("{} 转推失败（{}），{} 秒后重试", url, lastError, delay);
        }
        group.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /** 停止转推（流关闭时 MediaStream 也会调 {@link #close()}） */
    public void stop() {
        stopped = true;
        state = State.STOPPED;
        stream.removeSubscriber(this);
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
    }

    // ==================== 作为观看者收 FLV ====================

    @Override
    public boolean write(ByteBuf data) {
        try {
            Channel ch = channel;
            if (state != State.PUBLISHING || ch == null || !ch.isActive()) {
                return false;
            }
            if (!ch.isWritable()) {
                // 对端收得慢（带宽不够）：这一帧丢掉，MediaStream 会让我们等下一个关键帧再继续
                return false;
            }
            if (data.readableBytes() >= 3 && data.getByte(data.readerIndex()) == 'F'
                    && data.getByte(data.readerIndex() + 1) == 'L' && data.getByte(data.readerIndex() + 2) == 'V') {
                // FLV 文件头，RTMP 里不需要
                return true;
            }
            if (data.readableBytes() < 15) {
                return true;
            }
            int i = data.readerIndex();
            int type = data.getUnsignedByte(i);
            int size = data.getUnsignedMedium(i + 1);
            long ts = data.getUnsignedMedium(i + 4) | ((long) data.getUnsignedByte(i + 7) << 24);
            if (data.readableBytes() < 11 + size || (type != RtmpMessage.VIDEO && type != RtmpMessage.AUDIO)) {
                return true;
            }
            ByteBuf body = data.slice(i + 11, size);
            ByteBuf out = RtmpMessage.encode(ch.alloc(), type == RtmpMessage.VIDEO ? CSID_VIDEO : CSID_AUDIO,
                    type, streamId, ts, body, OUT_CHUNK);
            bytesOut += out.readableBytes();
            ch.writeAndFlush(out, ch.voidPromise());
            return true;
        } finally {
            data.release();
        }
    }

    @Override
    public void close() {
        // 流关了（没人看、终端停推）：转推也结束
        stopped = true;
        state = State.STOPPED;
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
    }

    @Override
    public String id() {
        return "转推 " + url;
    }

    @Override
    public boolean passive() {
        return true;
    }

    @Override
    public boolean flvAudio() {
        return true;
    }

    // ==================== 命令交互 ====================

    private final class Session extends SimpleChannelInboundHandler<RtmpMessage> implements RtmpDecoder.Handshake {

        @Override
        public ByteBuf onPeer01(ChannelHandlerContext ctx, ByteBuf s1) {
            // C2 就是把 S1 原样回过去
            return s1;
        }

        @Override
        public void done(ChannelHandlerContext ctx) {
            ByteBuf chunkSize = ctx.alloc().buffer(4).writeInt(OUT_CHUNK);
            send(ctx, CSID_CONTROL, RtmpMessage.SET_CHUNK_SIZE, 0, chunkSize);
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("app", app);
            obj.put("type", "nonprivate");
            obj.put("flashVer", "FMLE/3.0 (compatible; xiaowu-iot)");
            obj.put("tcUrl", tcUrl);
            command(ctx, CSID_COMMAND, 0, "connect", 1, obj);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RtmpMessage m) {
            try {
                switch (m.type) {
                    case RtmpMessage.USER_CONTROL:
                        // Ping 请求（事件 6）要回 Ping 响应（事件 7），否则有的服务器会断开
                        if (m.payload.readableBytes() >= 6 && m.payload.getUnsignedShort(m.payload.readerIndex()) == 6) {
                            ByteBuf pong = ctx.alloc().buffer(6);
                            pong.writeShort(7);
                            pong.writeInt(m.payload.getInt(m.payload.readerIndex() + 2));
                            send(ctx, CSID_CONTROL, RtmpMessage.USER_CONTROL, 0, pong);
                        }
                        break;
                    case RtmpMessage.COMMAND_AMF0:
                        onCommand(ctx, Amf0.readAll(m.payload));
                        break;
                    default:
                        // 设置 chunk 大小由解码器处理；窗口大小、带宽限制对推流方没影响
                        break;
                }
            } finally {
                m.payload.release();
            }
        }

        private void onCommand(ChannelHandlerContext ctx, List<Object> c) {
            if (c.isEmpty()) {
                return;
            }
            String name = String.valueOf(c.get(0));
            double txn = c.size() > 1 && c.get(1) instanceof Double ? (Double) c.get(1) : 0;
            if ("_result".equals(name) && txn == 1) {
                // connect 成功
                command(ctx, CSID_COMMAND, 0, "releaseStream", 2, null, streamName);
                command(ctx, CSID_COMMAND, 0, "FCPublish", 3, null, streamName);
                command(ctx, CSID_COMMAND, 0, "createStream", 4, (Object) null);
            } else if ("_result".equals(name) && txn == 4) {
                Object id = c.size() > 3 ? c.get(3) : null;
                streamId = id instanceof Double ? ((Double) id).intValue() : 1;
                command(ctx, CSID_STREAM, streamId, "publish", 5, null, streamName, "live");
            } else if ("onStatus".equals(name)) {
                Object info = c.size() > 3 ? c.get(3) : null;
                String code = info instanceof Map ? String.valueOf(((Map<?, ?>) info).get("code")) : "";
                String level = info instanceof Map ? String.valueOf(((Map<?, ?>) info).get("level")) : "";
                if ("NetStream.Publish.Start".equals(code)) {
                    onPublishing();
                } else if ("error".equals(level)) {
                    lastError = "对端拒绝：" + code + descriptionOf(info);
                    ctx.close();
                }
            } else if ("_error".equals(name)) {
                Object info = c.size() > 3 ? c.get(3) : null;
                lastError = "对端返回错误：" + (info instanceof Map ? ((Map<?, ?>) info).get("code") : "") + descriptionOf(info);
                ctx.close();
            }
        }

        private String descriptionOf(Object info) {
            Object d = info instanceof Map ? ((Map<?, ?>) info).get("description") : null;
            return d == null ? "" : "（" + d + "）";
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            lastError = cause.getClass().getSimpleName() + "：" + cause.getMessage();
            ctx.close();
        }
    }

    private void onPublishing() {
        state = State.PUBLISHING;
        publishedAt = System.currentTimeMillis();
        lastError = "";
        retryDelay = 2;
        if (retries > 0) {
            log.info("{} 转推已恢复", url);
        } else {
            log.info("开始转推 {} → {}", stream.getKey(), url);
        }
        retries = 0;
        // 加入流：马上收到序列头和整组画面
        stream.addSubscriber(this);
    }

    private void send(ChannelHandlerContext ctx, int csid, int type, int msid, ByteBuf payload) {
        try {
            ctx.writeAndFlush(RtmpMessage.encode(ctx.alloc(), csid, type, msid, 0, payload, type == RtmpMessage.SET_CHUNK_SIZE ? 128 : OUT_CHUNK));
        } finally {
            payload.release();
        }
    }

    private void command(ChannelHandlerContext ctx, int csid, int msid, String name, double txn, Object... args) {
        ByteBufAllocator alloc = ctx.alloc();
        ByteBuf p = alloc.buffer(128);
        Amf0.write(p, name);
        Amf0.write(p, txn);
        for (Object a : args) {
            Amf0.write(p, a);
        }
        send(ctx, csid, RtmpMessage.COMMAND_AMF0, msid, p);
    }

    // ==================== 状态 ====================

    public String getUrl() {
        return url;
    }

    public State getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public long getBytesOut() {
        return bytesOut;
    }

    public long getPublishedAt() {
        return publishedAt;
    }

    public String getStreamKey() {
        return stream.getKey();
    }
}
