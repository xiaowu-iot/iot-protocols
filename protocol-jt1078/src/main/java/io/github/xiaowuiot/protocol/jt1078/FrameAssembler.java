package io.github.xiaowuiot.protocol.jt1078;

import java.io.ByteArrayOutputStream;

/**
 * 把分包重组成完整的帧：第一包开始、中间包追加、最后一包结束；原子包本身就是一帧。
 * <p>
 * 丢包时（序号不连续、中间包前面没有第一包）整帧丢弃：残缺的 H.264 帧送给解码器会花屏，
 * 不如丢掉等下一帧。一个流一个实例，不是线程安全的。
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

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
    private boolean assembling;
    private int lastSeq = -1;
    private long dropped;

    /**
     * @return 收齐的一帧，没收齐返回 null
     */
    public Frame add(Jt1078Packet p) {
        boolean continuous = lastSeq < 0 || ((lastSeq + 1) & 0xFFFF) == p.seq;
        lastSeq = p.seq;
        switch (p.subpackage) {
            case Jt1078Packet.ATOMIC:
                reset();
                return new Frame(p.payloadType, p.dataType, p.timestamp, p.body);
            case Jt1078Packet.FIRST:
                if (assembling) {
                    dropped++;
                }
                reset();
                assembling = true;
                buf.write(p.body, 0, p.body.length);
                return null;
            case Jt1078Packet.MIDDLE:
            case Jt1078Packet.LAST:
                if (!assembling || !continuous || buf.size() + p.body.length > MAX_FRAME) {
                    if (assembling) {
                        dropped++;
                    }
                    reset();
                    return null;
                }
                buf.write(p.body, 0, p.body.length);
                if (p.subpackage == Jt1078Packet.MIDDLE) {
                    return null;
                }
                Frame f = new Frame(p.payloadType, p.dataType, p.timestamp, buf.toByteArray());
                reset();
                return f;
            default:
                return null;
        }
    }

    private void reset() {
        buf.reset();
        assembling = false;
    }

    /**
     * 因为丢包丢弃的帧数
     */
    public long dropped() {
        return dropped;
    }
}
