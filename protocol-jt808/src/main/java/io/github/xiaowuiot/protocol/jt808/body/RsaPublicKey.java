package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.math.BigInteger;

/**
 * RSA 公钥：平台 RSA 公钥 0x8A00、终端 RSA 公钥 0x0A00，格式一样。
 * <pre>
 * e DWORD | n BYTE[128]
 * </pre>
 * 双方交换公钥后，消息体属性的加密位 bit10 = 1 表示消息体用对方的公钥做了 RSA 加密。
 * 实际项目里几乎没有终端启用，这里只解析公钥本身。
 */
public class RsaPublicKey implements Jt808Body {

    private final long e;
    private final byte[] n;

    public RsaPublicKey(long e, byte[] n) {
        if (n.length != 128) {
            throw new IllegalArgumentException("n 必须是 128 字节：" + n.length);
        }
        this.e = e;
        this.n = n;
    }

    public static RsaPublicKey decode(ByteBuf b, Jt808Header h) {
        long e = b.readUnsignedInt();
        byte[] n = new byte[128];
        b.readBytes(n);
        return new RsaPublicKey(e, n);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        out.writeInt((int) e);
        out.writeBytes(n);
    }

    public long getE() {
        return e;
    }

    public byte[] getN() {
        return n;
    }

    public BigInteger getModulus() {
        return new BigInteger(1, n);
    }

    @Override
    public String toString() {
        return "RsaPublicKey{e=" + e + ", n=" + getModulus().toString(16) + '}';
    }
}
