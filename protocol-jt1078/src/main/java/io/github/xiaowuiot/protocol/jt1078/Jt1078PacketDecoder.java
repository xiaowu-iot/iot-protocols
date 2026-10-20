package io.github.xiaowuiot.protocol.jt1078;

import io.github.xiaowuiot.protocol.codec.frame.AbstractFrameDecoder;
import io.github.xiaowuiot.protocol.codec.frame.DiscardReason;
import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * 1078 码流切包：找帧头 30 31 63 64，按数据类型算出头部长度，再按"数据体长度"取完整的包。
 * <p>
 * SIM 卡号的长度：2016 版是 6 字节；部分按 808-2019 做的终端是 10 字节。simLength 传 0 表示自动识别：
 * 两种长度各算一遍，看哪种的"数据类型、分包标记、数据体长度"合理，并且包尾正好接着下一个帧头。
 */
public class Jt1078PacketDecoder extends AbstractFrameDecoder {

    private static final byte[] MAGIC = {0x30, 0x31, 0x63, 0x64};

    private int simLength;

    public Jt1078PacketDecoder(int simLength) {
        this.simLength = simLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            if (!skipToMarker(ctx, in, MAGIC)) {
                return;
            }
            int start = in.readerIndex();
            if (in.readableBytes() < 30) {
                return;
            }
            int sl = simLength > 0 ? simLength : detect(in, start);
            if (sl < 0) {
                // 还判断不出来，等更多数据
                return;
            }
            if (sl == 0) {
                discard(ctx, in, 4, DiscardReason.BAD_LENGTH);
                continue;
            }
            simLength = sl;
            int typeByte = in.getUnsignedByte(start + 8 + sl + 1);
            int dataType = typeByte >> 4;
            int headerLength = Jt1078Packet.headerLength(sl, dataType);
            if (in.readableBytes() < headerLength) {
                return;
            }
            int bodyLength = in.getUnsignedShort(start + headerLength - 2);
            if (dataType > Jt1078Packet.TYPE_PASSTHROUGH || bodyLength > 4096) {
                discard(ctx, in, 4, DiscardReason.BAD_LENGTH);
                continue;
            }
            if (in.readableBytes() < headerLength + bodyLength) {
                return;
            }
            in.skipBytes(4);
            in.skipBytes(1);
            int mpt = in.readUnsignedByte();
            int seq = in.readUnsignedShort();
            String sim = BcdUtils.readBcd(in, sl);
            int channel = in.readUnsignedByte();
            in.skipBytes(1);
            long ts = dataType != Jt1078Packet.TYPE_PASSTHROUGH ? in.readLong() : 0;
            int lastI = 0;
            int last = 0;
            if (dataType <= Jt1078Packet.TYPE_B) {
                lastI = in.readUnsignedShort();
                last = in.readUnsignedShort();
            }
            in.skipBytes(2);
            byte[] body = new byte[bodyLength];
            in.readBytes(body);
            out.add(new Jt1078Packet(mpt & 0x7F, (mpt & 0x80) != 0, seq, sim, channel, dataType, typeByte & 0x0F, ts,
                    lastI, last, body));
        }
    }

    /**
     * @return 6 或 10；0 表示两种都不对；-1 表示数据不够，还判断不了
     */
    static int detect(ByteBuf in, int start) {
        boolean need = false;
        for (int sl : new int[]{6, 10}) {
            int typeIndex = start + 8 + sl + 1;
            if (typeIndex >= in.writerIndex()) {
                need = true;
                continue;
            }
            int typeByte = in.getUnsignedByte(typeIndex);
            int dataType = typeByte >> 4;
            int sub = typeByte & 0x0F;
            if (dataType > Jt1078Packet.TYPE_PASSTHROUGH || sub > Jt1078Packet.MIDDLE) {
                continue;
            }
            int headerLength = Jt1078Packet.headerLength(sl, dataType);
            if (start + headerLength > in.writerIndex()) {
                need = true;
                continue;
            }
            int bodyLength = in.getUnsignedShort(start + headerLength - 2);
            int end = start + headerLength + bodyLength;
            if (bodyLength > 4096) {
                continue;
            }
            if (end + 4 <= in.writerIndex()) {
                if (in.getInt(end) == Jt1078Packet.MAGIC) {
                    return sl;
                }
                continue;
            }
            need = true;
        }
        return need ? -1 : 0;
    }

    public int getSimLength() {
        return simLength;
    }
}
