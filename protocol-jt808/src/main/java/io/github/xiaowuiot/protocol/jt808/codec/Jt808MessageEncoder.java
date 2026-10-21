package io.github.xiaowuiot.protocol.jt808.codec;

import io.github.xiaowuiot.protocol.codec.util.Checksums;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.body.Jt808Body;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * {@link Jt808Message} → 消息头 + 消息体 + 校验码。
 * <p>
 * 输出的还没有转义、没有首尾 7E，由后面的 {@code EscapedDelimiterFrameEncoder} 完成。
 * 注意顺序：<b>先算校验，再转义</b>，和解码时正好相反。
 * <p>
 * 消息体长度在编码完消息体之后回填，调用方不需要自己算。无状态，可以共享。
 */
@ChannelHandler.Sharable
public class Jt808MessageEncoder extends MessageToByteEncoder<Jt808Message> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Jt808Message msg, ByteBuf out) {
        Jt808Rsa rsa = ctx.channel().attr(Jt808Rsa.KEY).get();
        java.security.PublicKey terminalKey = ctx.channel().attr(Jt808Rsa.TERMINAL_KEY).get();
        Jt808Header h = msg.getHeader();
        boolean wanted = (h.getEncryption() & Jt808Rsa.RSA) != 0
                || (rsa != null && rsa.isEncryptDownlink() && h.getMsgId() != io.github.xiaowuiot.protocol.jt808.MessageId.PLATFORM_RSA_KEY);
        if (!wanted || terminalKey == null || rsa == null) {
            if ((h.getEncryption() & Jt808Rsa.RSA) != 0) {
                // 要求加密但还没拿到终端公钥：按明文发，不能把加密位带出去
                h.setEncryption(h.getEncryption() & ~Jt808Rsa.RSA);
            }
            encode(msg, out);
            return;
        }
        // RSA 加密：先把消息体按明文编出来，加密后作为原始字节写出，加密位置 1
        ByteBuf plain = ctx.alloc().buffer();
        try {
            Object body = msg.getBody();
            if (body instanceof Jt808Body) {
                ((Jt808Body) body).encode(plain, h);
            } else if (body instanceof byte[]) {
                plain.writeBytes((byte[]) body);
            }
            byte[] cipherText = rsa.encrypt(terminalKey, ByteBufUtil.getBytes(plain));
            h.setEncryption(h.getEncryption() | Jt808Rsa.RSA);
            encode(new Jt808Message(h, cipherText), out);
        } finally {
            plain.release();
        }
    }

    public static void encode(Jt808Message msg, ByteBuf out) {
        Jt808Header header = msg.getHeader();
        int start = out.writerIndex();
        header.setBodyLength(0);
        header.write(out);
        int bodyStart = out.writerIndex();

        Object body = msg.getBody();
        if (body instanceof Jt808Body) {
            ((Jt808Body) body).encode(out, header);
        } else if (body instanceof byte[]) {
            out.writeBytes((byte[]) body);
        } else if (body != null) {
            throw new IllegalArgumentException("不支持的消息体类型：" + body.getClass());
        }

        int bodyLength = out.writerIndex() - bodyStart;
        header.setBodyLength(bodyLength);
        if (bodyLength > Jt808Header.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(String.format("0x%04X 消息体 %d 字节，超过 1023 字节，需要分包发送",
                    header.getMsgId(), bodyLength));
        }
        // 回填消息体属性里的长度：低 10 位
        int attr = out.getUnsignedShort(start + 2);
        out.setShort(start + 2, (attr & ~Jt808Header.MAX_BODY_LENGTH) | bodyLength);
        out.writeByte(Checksums.bcc(out, start, out.writerIndex() - start));
    }
}
