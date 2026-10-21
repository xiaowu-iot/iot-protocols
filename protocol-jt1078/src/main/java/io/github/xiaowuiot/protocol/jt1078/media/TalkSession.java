package io.github.xiaowuiot.protocol.jt1078.media;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双向对讲的一个浏览器连接（WebSocket）。
 * <pre>
 * 浏览器麦克风 ─PCM 16 位 8000 Hz─▶ 这里 ─G.711A 1078 音频包─▶ 终端（走终端推流的那条连接）
 * 浏览器喇叭   ◀─和 .audio 一样的分块─ 这里 ◀─终端上行音频（MediaStream 解码）
 * </pre>
 * 同时是这路流的观看者（算人数，对讲没结束就不会因为没人看被关掉）和音频收听者。
 * 浏览器发来的 PCM 按 40 毫秒（320 个采样）一帧切开再编码，帧太碎终端播放会卡。
 */
final class TalkSession extends SimpleChannelInboundHandler<WebSocketFrame> implements MediaStream.Subscriber {

    private static final Logger log = LoggerFactory.getLogger(TalkSession.class);
    private static final int FRAME_BYTES = 640;

    private final String key;
    private final StreamHub hub;
    private final MediaServer server;
    private Channel channel;
    private final ByteBuf pending = Unpooled.buffer(FRAME_BYTES * 4);
    private long frames;

    TalkSession(String key, StreamHub hub, MediaServer server) {
        this.key = key;
        this.hub = hub;
        this.server = server;
    }

    /** 握手完成后调用：登记成观看者和收听者 */
    void start(Channel ch) {
        channel = ch;
        hub.subscribe(key, this);
        hub.subscribeAudio(key, this);
        log.info("{} 开始对讲 {}", ch.remoteAddress(), key);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof BinaryWebSocketFrame) {
            ByteBuf in = frame.content();
            if (pending.readableBytes() + in.readableBytes() > FRAME_BYTES * 50) {
                // 终端那边写不出去、浏览器还在一直发：丢掉积压，保持实时
                pending.clear();
            }
            pending.writeBytes(in);
            while (pending.readableBytes() >= FRAME_BYTES) {
                byte[] pcm = new byte[FRAME_BYTES];
                pending.readBytes(pcm);
                if (server.sendAudio(key, AudioCodec.pcmToAlaw(pcm))) {
                    frames++;
                }
            }
            pending.discardReadBytes();
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof CloseWebSocketFrame) {
            ctx.writeAndFlush(frame.retainedDuplicate()).addListener(f -> ctx.close());
        } else if (frame instanceof TextWebSocketFrame) {
            // 浏览器心跳，忽略
        }
    }

    // ==================== 作为观看者 / 收听者 ====================

    @Override
    public boolean write(ByteBuf data) {
        Channel c = channel;
        // 视频 FLV 不需要；音频分块（类型 1 PCM / 2 AAC）原样转给浏览器
        if (c == null || !c.isActive() || data.readableBytes() < 4 || isFlv(data) || !c.isWritable()) {
            data.release();
            return true;
        }
        int type = data.getUnsignedByte(data.readerIndex());
        if (type != MediaStream.AUDIO_PCM && type != MediaStream.AUDIO_AAC) {
            data.release();
            return true;
        }
        c.writeAndFlush(new BinaryWebSocketFrame(data), c.voidPromise());
        return true;
    }

    /** 视频观看者收到的是 FLV 头和 FLV tag（类型 8 / 9 / 18），不是音频分块 */
    private static boolean isFlv(ByteBuf b) {
        int t = b.getUnsignedByte(b.readerIndex());
        return t == 'F' || t == 8 || t == 9 || t == 18;
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
        return "对讲 " + (channel == null ? "?" : channel.remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        hub.unsubscribeAudio(key, this);
        hub.unsubscribe(key, this);
        pending.release();
        log.info("{} 结束对讲 {}，发给终端 {} 帧", ctx.channel().remoteAddress(), key, frames);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
