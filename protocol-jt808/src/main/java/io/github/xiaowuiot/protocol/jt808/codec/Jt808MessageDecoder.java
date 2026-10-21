package io.github.xiaowuiot.protocol.jt808.codec;

import io.github.xiaowuiot.protocol.codec.session.Session;
import io.github.xiaowuiot.protocol.codec.stats.CodecStats;
import io.github.xiaowuiot.protocol.codec.util.Checksums;
import io.github.xiaowuiot.protocol.codec.util.HexUtils;
import io.github.xiaowuiot.protocol.codec.util.RateLimitedLog;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.Jt808Version;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.BodyDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 帧 → {@link Jt808Message}。
 * <p>
 * 输入是帧解码器切好、已经还原了转义的一帧：消息头 + 消息体 + 校验码。按顺序做这几件事：
 * <ol>
 *     <li>校验 BCC</li>
 *     <li>解析消息头，bit14 区分 2019 版；2011 版按连接上记录的版本修正</li>
 *     <li>核对消息体长度</li>
 *     <li>分包的先缓存，收齐后合并</li>
 *     <li>按消息 ID 找到消息体解码器</li>
 * </ol>
 * 任何一步失败都<b>丢弃这一帧、打日志，不抛异常、不断开连接</b>：一帧坏数据不应该让设备掉线。
 * <p>
 * 有状态（分包缓存），每条连接 new 一个。
 */
public class Jt808MessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(Jt808MessageDecoder.class);
    /** 坏帧可能是持续的（固件 bug、有人乱发），日志要限流，否则能刷满磁盘、拖慢 IO 线程 */
    private static final RateLimitedLog DROP_LOG = new RateLimitedLog(log, 1000);
    private static final RateLimitedLog BODY_LOG = new RateLimitedLog(log, 1000);

    /**
     * 最短的一帧：2013 版消息头 12 字节 + 校验码 1 字节
     */
    private static final int MIN_FRAME_LENGTH = 13;

    private final SubpackageAssembler assembler;

    public Jt808MessageDecoder() {
        this(new SubpackageAssembler(TimeUnit.SECONDS.toMillis(60), 4096));
    }

    public Jt808MessageDecoder(SubpackageAssembler assembler) {
        this.assembler = assembler;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        if (frame.readableBytes() < MIN_FRAME_LENGTH) {
            drop(ctx, frame, "长度不足");
            return;
        }
        int start = frame.readerIndex();
        int checkIndex = frame.writerIndex() - 1;
        int expected = frame.getUnsignedByte(checkIndex);
        int actual = Checksums.bcc(frame, start, checkIndex - start);
        if (expected != actual) {
            drop(ctx, frame, String.format("校验失败，报文中是 %02X，计算结果是 %02X", expected, actual));
            return;
        }

        Jt808Header header;
        try {
            header = Jt808Header.read(frame);
        } catch (IndexOutOfBoundsException e) {
            drop(ctx, frame.readerIndex(start), "消息头不完整");
            return;
        }
        if (!header.isV2019() && Jt808Codecs.getVersion(ctx.channel()) == Jt808Version.V2011) {
            header.setVersion(Jt808Version.V2011);
        }
        int bodyLength = checkIndex - frame.readerIndex();
        if (bodyLength != header.getBodyLength()) {
            drop(ctx, frame.readerIndex(start), "消息体长度不符，属性里是 " + header.getBodyLength() + "，实际 " + bodyLength);
            return;
        }
        ByteBuf body = frame.slice(frame.readerIndex(), bodyLength);
        if (header.getEncryption() != 0) {
            Jt808Rsa rsa = ctx.channel().attr(Jt808Rsa.KEY).get();
            if ((header.getEncryption() & Jt808Rsa.RSA) == 0 || rsa == null) {
                drop(ctx, frame.readerIndex(start), rsa == null ? "收到加密的消息，但没有配置平台 RSA 密钥" : "不支持的加密方式 " + header.getEncryption());
                return;
            }
            try {
                byte[] plain = rsa.decrypt(ByteBufUtil.getBytes(body));
                body = io.netty.buffer.Unpooled.wrappedBuffer(plain);
                header.setBodyLength(plain.length);
            } catch (IllegalArgumentException e) {
                drop(ctx, frame.readerIndex(start), e.getMessage());
                return;
            }
        }

        if (header.isSubpackage()) {
            if (ctx.channel().parent() != null && ctx.channel().attr(Session.KEY).get() == null) {
                // 服务端接入的连接（有父 Channel）：注册、鉴权都不分包，没鉴权就发分包的不缓存，防止被拿来占内存。
                // 终端、模拟器这一侧收平台下发的分包时没有会话，不受影响
                drop(ctx, frame.readerIndex(start), "未鉴权的连接不接收分包");
                return;
            }
            ByteBuf merged;
            try {
                merged = assembler.add(header, body, ctx.alloc());
            } catch (IllegalArgumentException e) {
                drop(ctx, frame.readerIndex(start), e.getMessage());
                return;
            }
            if (merged == null) {
                out.add(Jt808Message.fragment(header));
                return;
            }
            try {
                header.setBodyLength(merged.readableBytes());
                decodeBody(ctx, header, merged, out);
            } finally {
                merged.release();
            }
            return;
        }
        decodeBody(ctx, header, body, out);
    }

    private void decodeBody(ChannelHandlerContext ctx, Jt808Header header, ByteBuf body, List<Object> out) {
        BodyDecoder<?> decoder = BodyDecoders.get(header.getMsgId());
        if (decoder == null) {
            out.add(new Jt808Message(header, ByteBufUtil.getBytes(body)));
            return;
        }
        int bodyStart = body.readerIndex();
        try {
            out.add(new Jt808Message(header, decoder.decode(body, header)));
            if (header.getMsgId() == MessageId.REGISTER && header.isV2011()) {
                // 注册消息的长度认出了 2011 版，这条连接后面的消息都按 2011 版解析
                Jt808Codecs.setVersion(ctx.channel(), Jt808Version.V2011);
            }
        } catch (RuntimeException e) {
            // 消息体字段和协议不符，多半是厂家固件和文档不一致，原始报文一定要打出来
            CodecStats.increment("jt808.body");
            BODY_LOG.warn("消息体解析失败", ctx.channel() + " " + header + "，消息体：" + HexUtils.toHex(body.readerIndex(bodyStart)), e);
            out.add(Jt808Message.malformed(header));
        }
    }

    private static void drop(ChannelHandlerContext ctx, ByteBuf frame, String reason) {
        CodecStats.increment("jt808.drop");
        DROP_LOG.warn("丢弃一帧", ctx.channel() + " " + reason + "，内容：" + HexUtils.toHex(frame), null);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        assembler.releaseAll();
    }
}
