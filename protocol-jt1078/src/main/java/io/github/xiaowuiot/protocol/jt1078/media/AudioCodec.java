package io.github.xiaowuiot.protocol.jt1078.media;

import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;

/**
 * 1078 终端常见的音频格式转成 16 位 PCM（8000 Hz 单声道，小端），浏览器用 WebAudio 直接播。
 * <p>
 * 浏览器的 MSE 不支持 G.711 / ADPCM，又不想引入转码组件，所以在服务端解码：这几种格式解码只是查表和移位，
 * 一路音频每秒 8000 个采样点，开销可以忽略。AAC 浏览器能解（WebCodecs），原样转发。
 * <p>
 * 很多终端（海思方案）在每帧音频前面加 4 字节的海思头：{@code 00 01 长度/2 00}，解码前要去掉。
 */
public final class AudioCodec {

    public static final int SAMPLE_RATE = 8000;

    private static final short[] ALAW = new short[256];
    private static final short[] ULAW = new short[256];
    private static final int[] STEP = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
            5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
            27086, 29794, 32767};
    private static final int[] INDEX = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};

    static {
        for (int i = 0; i < 256; i++) {
            int a = i ^ 0x55;
            int t = (a & 0x0F) << 4;
            int seg = (a & 0x70) >> 4;
            if (seg == 0) {
                t += 8;
            } else {
                t = (t + 0x108) << (seg - 1);
            }
            ALAW[i] = (short) ((a & 0x80) != 0 ? t : -t);

            int u = ~i & 0xFF;
            int v = ((u & 0x0F) << 3) + 0x84;
            v <<= (u & 0x70) >> 4;
            ULAW[i] = (short) ((u & 0x80) != 0 ? 0x84 - v : v - 0x84);
        }
    }

    private AudioCodec() {
    }

    /** 能在服务端解成 PCM 的格式 */
    public static boolean decodable(int payloadType) {
        return payloadType == Jt1078Packet.PT_G711A || payloadType == Jt1078Packet.PT_G711U
                || payloadType == Jt1078Packet.PT_ADPCMA;
    }

    /**
     * 解成 16 位小端 PCM
     *
     * @return 不支持的格式返回 null
     */
    public static byte[] toPcm(int payloadType, byte[] frame) {
        byte[] data = stripHisiHeader(frame);
        switch (payloadType) {
            case Jt1078Packet.PT_G711A:
                return table(data, ALAW);
            case Jt1078Packet.PT_G711U:
                return table(data, ULAW);
            case Jt1078Packet.PT_ADPCMA:
                return imaAdpcm(data);
            default:
                return null;
        }
    }

    /** 海思头：00 01 后面 1 字节是（去掉头以后的长度 / 2），再 1 字节 00 */
    static byte[] stripHisiHeader(byte[] f) {
        if (f.length > 4 && f[0] == 0 && f[1] == 1 && f[3] == 0 && (f[2] & 0xFF) * 2 == f.length - 4) {
            byte[] out = new byte[f.length - 4];
            System.arraycopy(f, 4, out, 0, out.length);
            return out;
        }
        return f;
    }

    private static byte[] table(byte[] in, short[] t) {
        byte[] out = new byte[in.length * 2];
        for (int i = 0; i < in.length; i++) {
            short s = t[in[i] & 0xFF];
            out[2 * i] = (byte) s;
            out[2 * i + 1] = (byte) (s >> 8);
        }
        return out;
    }

    /**
     * IMA ADPCM（1078 里的 ADPCMA）。每帧开头 4 字节状态：上一个采样值（16 位小端）、步长索引、保留，
     * 后面每字节两个采样，低 4 位在前。
     */
    static byte[] imaAdpcm(byte[] in) {
        if (in.length < 4) {
            return new byte[0];
        }
        int predictor = (short) ((in[0] & 0xFF) | (in[1] << 8));
        int index = Math.min(88, Math.max(0, in[2] & 0xFF));
        byte[] out = new byte[(in.length - 4) * 4];
        int o = 0;
        for (int i = 4; i < in.length; i++) {
            for (int half = 0; half < 2; half++) {
                int nibble = half == 0 ? in[i] & 0x0F : (in[i] >> 4) & 0x0F;
                int step = STEP[index];
                int diff = step >> 3;
                if ((nibble & 4) != 0) {
                    diff += step;
                }
                if ((nibble & 2) != 0) {
                    diff += step >> 1;
                }
                if ((nibble & 1) != 0) {
                    diff += step >> 2;
                }
                predictor += (nibble & 8) != 0 ? -diff : diff;
                predictor = Math.max(-32768, Math.min(32767, predictor));
                index = Math.max(0, Math.min(88, index + INDEX[nibble]));
                out[o++] = (byte) predictor;
                out[o++] = (byte) (predictor >> 8);
            }
        }
        return out;
    }

    /** 测试和模拟器用：16 位 PCM 编成 G.711A */
    public static byte[] pcmToAlaw(short[] pcm) {
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = alaw(pcm[i]);
        }
        return out;
    }

    private static byte alaw(int pcm) {
        int mask;
        if (pcm >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            pcm = -pcm - 1;
        }
        int[] seg = {0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF};
        int s = 0;
        while (s < 8 && pcm > seg[s]) {
            s++;
        }
        if (s >= 8) {
            return (byte) (0x7F ^ mask);
        }
        int a = s << 4;
        a |= s < 2 ? (pcm >> 4) & 0x0F : (pcm >> (s + 3)) & 0x0F;
        return (byte) (a ^ mask);
    }
}
