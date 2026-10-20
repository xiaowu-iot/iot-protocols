package io.github.xiaowuiot.protocol.jt1078;

import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.netty.buffer.ByteBuf;

/**
 * 1078 码流的一个 RTP 包（终端推给媒体服务器，不是 808 帧：没有 7E、不转义、没有校验）。
 * <pre>
 * 偏移 长度
 *  0   4   帧头标识 0x30 0x31 0x63 0x64
 *  4   1   V(2) P(1) X(1) CC(4)，固定 0x81
 *  5   1   M(1) PT(7)：M 是帧边界标记，PT 是负载类型（98 H.264、99 H.265、6 G.711A……）
 *  6   2   包序号
 *  8   6   SIM 卡号 BCD（部分 2019 终端是 10 字节，见 simLength）
 * 14   1   逻辑通道号
 * 15   1   数据类型(高 4 位：0 I 帧 1 P 帧 2 B 帧 3 音频 4 透传) | 分包处理标记(低 4 位：0 原子包 1 第一包 2 最后一包 3 中间包)
 * 16   8   时间戳（毫秒），透传数据没有
 * 24   2   与上一关键帧的间隔（毫秒），只有视频帧有
 * 26   2   与上一帧的间隔（毫秒），只有视频帧有
 * 28   2   数据体长度（≤ 950）
 * 30   n   数据体
 * </pre>
 * 一帧视频通常要拆成很多包，靠分包标记重组，见 {@link FrameAssembler}。
 */
public final class Jt1078Packet {

    public static final int MAGIC = 0x30316364;
    public static final int MAX_BODY = 950;

    public static final int TYPE_I = 0;
    public static final int TYPE_P = 1;
    public static final int TYPE_B = 2;
    public static final int TYPE_AUDIO = 3;
    public static final int TYPE_PASSTHROUGH = 4;

    public static final int ATOMIC = 0;
    public static final int FIRST = 1;
    public static final int LAST = 2;
    public static final int MIDDLE = 3;

    public static final int PT_G711A = 6;
    public static final int PT_G711U = 7;
    public static final int PT_ADPCMA = 26;
    public static final int PT_AAC = 19;
    public static final int PT_H264 = 98;
    public static final int PT_H265 = 99;

    public final int payloadType;
    public final boolean marker;
    public final int seq;
    public final String sim;
    public final int channel;
    public final int dataType;
    public final int subpackage;
    public final long timestamp;
    public final int lastIFrameInterval;
    public final int lastFrameInterval;
    public final byte[] body;

    public Jt1078Packet(int payloadType, boolean marker, int seq, String sim, int channel, int dataType, int subpackage,
                        long timestamp, int lastIFrameInterval, int lastFrameInterval, byte[] body) {
        this.payloadType = payloadType;
        this.marker = marker;
        this.seq = seq;
        this.sim = sim;
        this.channel = channel;
        this.dataType = dataType;
        this.subpackage = subpackage;
        this.timestamp = timestamp;
        this.lastIFrameInterval = lastIFrameInterval;
        this.lastFrameInterval = lastFrameInterval;
        this.body = body;
    }

    public boolean isVideo() {
        return dataType <= TYPE_B;
    }

    /**
     * 流的标识：SIM 卡号去掉前导 0 + 通道号，比如 13800138000_1
     */
    public String streamKey() {
        return streamKey(sim, channel);
    }

    public static String streamKey(String sim, int channel) {
        String s = sim;
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i) + "_" + channel;
    }

    /**
     * 头部长度：透传没有时间戳；音频没有两个帧间隔
     */
    public static int headerLength(int simLength, int dataType) {
        int n = 4 + 1 + 1 + 2 + simLength + 1 + 1;
        if (dataType != TYPE_PASSTHROUGH) {
            n += 8;
        }
        if (dataType <= TYPE_B) {
            n += 4;
        }
        return n + 2;
    }

    public void encode(ByteBuf out, int simLength) {
        out.writeInt(MAGIC);
        out.writeByte(0x81);
        out.writeByte((marker ? 0x80 : 0) | (payloadType & 0x7F));
        out.writeShort(seq);
        BcdUtils.writeBcd(out, sim, simLength);
        out.writeByte(channel);
        out.writeByte(dataType << 4 | subpackage);
        if (dataType != TYPE_PASSTHROUGH) {
            out.writeLong(timestamp);
        }
        if (dataType <= TYPE_B) {
            out.writeShort(lastIFrameInterval);
            out.writeShort(lastFrameInterval);
        }
        out.writeShort(body.length);
        out.writeBytes(body);
    }

    public static String payloadName(int pt) {
        switch (pt) {
            case PT_G711A:
                return "G.711A";
            case PT_G711U:
                return "G.711U";
            case PT_ADPCMA:
                return "ADPCMA";
            case PT_AAC:
                return "AAC";
            case PT_H264:
                return "H.264";
            case PT_H265:
                return "H.265";
            default:
                return "PT" + pt;
        }
    }

    @Override
    public String toString() {
        return "Jt1078Packet{" + streamKey() + ", " + payloadName(payloadType) + ", seq=" + seq + ", type=" + dataType
                + ", sub=" + subpackage + ", ts=" + timestamp + ", len=" + body.length + '}';
    }
}
