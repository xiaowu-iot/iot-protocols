package io.github.xiaowuiot.protocol.jt808.codec;

import io.github.xiaowuiot.protocol.jt808.body.RsaPublicKey;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

/**
 * 808 的 RSA 消息体加密（消息体属性 bit10 = 1）。
 * <p>
 * 流程：终端用 0x0A00 上报自己的公钥，平台用 0x8A00 下发平台公钥；之后发给对方的消息体用<b>对方的公钥</b>加密，
 * 收到的用自己的私钥解密。公钥格式：e 4 字节 + n 128 字节（RSA 1024）。
 * <p>
 * 标准没有规定分块和填充方式，各家终端的实现不完全一样。这里用最常见的做法：RSA/ECB/PKCS1Padding，
 * 明文按 117 字节分块，每块加密成 128 字节。对接前要和终端厂家核对，不一致时继承这个类覆盖 {@link #encrypt} / {@link #decrypt}。
 * <p>
 * 一个平台一个实例（平台密钥对），终端的公钥记在各自的连接上。
 */
public class Jt808Rsa {

    /** 连接上的 RSA 配置：服务端装配流水线时设置 */
    public static final AttributeKey<Jt808Rsa> KEY = AttributeKey.valueOf("jt808.rsa");
    /** 这条连接的终端公钥（收到 0x0A00 后记下） */
    public static final AttributeKey<PublicKey> TERMINAL_KEY = AttributeKey.valueOf("jt808.terminalRsaKey");

    /** 加密方式里 RSA 那一位（消息体属性 bit10，在 3 位加密方式字段里是最低位） */
    public static final int RSA = 1;

    private static final int KEY_BYTES = 128;
    private static final int PLAIN_BLOCK = KEY_BYTES - 11;

    private final KeyPair platform;
    private final boolean encryptDownlink;

    /**
     * @param platform        平台密钥对（RSA 1024），要持久化保存，重启后不能变，否则终端手里的平台公钥就失效了
     * @param encryptDownlink 拿到终端公钥后，平台下发的消息是否加密
     */
    public Jt808Rsa(KeyPair platform, boolean encryptDownlink) {
        this.platform = platform;
        this.encryptDownlink = encryptDownlink;
    }

    /** 生成一对新的 RSA 1024 密钥（调试用；生产环境生成一次后保存起来） */
    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(KEY_BYTES * 8);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isEncryptDownlink() {
        return encryptDownlink;
    }

    /** 平台公钥，0x8A00 的消息体 */
    public RsaPublicKey platformPublicKey() {
        RSAPublicKey k = (RSAPublicKey) platform.getPublic();
        return new RsaPublicKey(k.getPublicExponent().longValue(), unsigned(k.getModulus(), KEY_BYTES));
    }

    /** 0x0A00 里的终端公钥转成 Java 的公钥 */
    public static PublicKey toPublicKey(RsaPublicKey k) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, k.getN()), BigInteger.valueOf(k.getE())));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("终端公钥不合法：" + e.getMessage(), e);
        }
    }

    public static void setTerminalKey(Channel ch, RsaPublicKey k) {
        ch.attr(TERMINAL_KEY).set(toPublicKey(k));
    }

    /** 用平台私钥解开终端发来的消息体 */
    public byte[] decrypt(byte[] cipherText) {
        if (cipherText.length % KEY_BYTES != 0) {
            throw new IllegalArgumentException("密文长度 " + cipherText.length + " 不是 " + KEY_BYTES + " 的整数倍");
        }
        return blocks(Cipher.DECRYPT_MODE, platform.getPrivate(), cipherText, KEY_BYTES);
    }

    /** 用终端公钥加密发给它的消息体 */
    public byte[] encrypt(PublicKey terminal, byte[] plain) {
        return blocks(Cipher.ENCRYPT_MODE, terminal, plain, PLAIN_BLOCK);
    }

    private static byte[] blocks(int mode, java.security.Key key, byte[] in, int block) {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(mode, key);
            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length + KEY_BYTES);
            for (int i = 0; i < in.length; i += block) {
                out.write(c.doFinal(in, i, Math.min(block, in.length - i)));
            }
            return out.toByteArray();
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalArgumentException("RSA " + (mode == Cipher.DECRYPT_MODE ? "解密" : "加密") + "失败：" + e.getMessage(), e);
        }
    }

    private static byte[] unsigned(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) {
            return b;
        }
        if (b.length == len + 1 && b[0] == 0) {
            return Arrays.copyOfRange(b, 1, b.length);
        }
        byte[] out = new byte[len];
        System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }

    /** 测试和模拟器用：平台私钥（模拟器扮演平台时） */
    PrivateKey privateKey() {
        return platform.getPrivate();
    }
}
