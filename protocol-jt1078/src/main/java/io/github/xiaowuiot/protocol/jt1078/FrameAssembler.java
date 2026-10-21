package io.github.xiaowuiot.protocol.jt1078;

import java.io.ByteArrayOutputStream;

/**
 * 把分包重组成完整的帧：第一包开始、中间包追加、最后一包结束；原子包本身就是一帧。
 * <p>
 * 丢包时（序号不连续、中间包前面没有第一包）整帧丢弃：残缺的 H.264 帧送给解码器会花屏，
 * 不如丢掉等下一帧。一个流一个实例，不是线程安全的。
 * <p>
 * 包序号是这个通道所有 RTP 包共用的（视频、音频、透传混在一起递增），所以序号连续性按全部包判断；
 * 视频和音频各拼各的，音频包插在视频分包中间不会把拼到一半的视频帧冲掉。
 */
public class FrameAssembler {

    /** 一帧最大 2 MB，超过说明数据不对 */
    private static final int MAX_FRAME = 2 * 1024 * 1024;

    public static final class Frame {
        public final int payloadType;
        public final int dataType;
        public final long timestamp;
        public final byte[] data;

        Frame(int payloadType, int dataType, long timestamp, byte[] data) {
            this.payloadType = payloadType;
            this.dataType = dataType;
            this.timestamp = timestamp;
            this.data = data;
        }

        public boolean isKey() {
            return dataType == Jt1078Packet.TYPE_I;
        }

        public boolean isVideo() {
            return dataType <= Jt1078Packet.TYPE_B;
        }
    }

    private static final class Part {
        final ByteArrayOutputStream buf;
        boolean assembling;

        Part(int size) {
            buf = new ByteArrayOutputStream(size);
        }

        void reset() {
            buf.reset();
            assembling = false;
        }
    }

    private final Part video = new Part(64 * 1024);
    private final Part audio = new Part(1024);
    private int lastSeq = -1;
    private long dropped;

    /**
     * @return 收齐的一帧，没收齐返回 null
     */
    public Frame add(Jt1078Packet p) {
        boolean continuous = lastSeq < 0 || ((lastSeq + 1) & 0xFFFF) == p.seq;
        lastSeq = p.seq;
        if (p.dataType == Jt1078Packet.TYPE_PASSTHROUGH) {
            // 透传数据不拼帧，只占一个序号
            return null;
        }
        Part part = p.dataType <= Jt1078Packet.TYPE_B ? video : audio;
        switch (p.subpackage) {
            case Jt1078Packet.ATOMIC:
                if (part.assembling) {
                    dropped++;
                }
                part.reset();
                return new Frame(p.payloadType, p.dataType, p.timestamp, p.body);
            case Jt1078Packet.FIRST:
                if (part.assembling) {
                    dropped++;
                }
                part.reset();
                part.assembling = true;
                part.buf.write(p.body, 0, p.body.length);
                return null;
            case Jt1078Packet.MIDDLE:
            case Jt1078Packet.LAST:
                if (!part.assembling || !continuous || part.buf.size() + p.body.length > MAX_FRAME) {
                    if (part.assembling) {
                        dropped++;
                    }
                    part.reset();
                    return null;
                }
                part.buf.write(p.body, 0, p.body.length);
                if (p.subpackage == Jt1078Packet.MIDDLE) {
                    return null;
                }
                Frame f = new Frame(p.payloadType, p.dataType, p.timestamp, part.buf.toByteArray());
                part.reset();
                return f;
            default:
                return null;
        }
    }

    /**
     * 因为丢包丢弃的帧数
     */
    public long dropped() {
        return dropped;
    }
}
