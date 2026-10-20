package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CAN 总线数据上传 0x0705。
 * <pre>
 * 数据项个数 WORD | 第一项的接收时间 BCD[5]（hh mm ss msms，毫秒占 2 个字节）
 * | 数据项：CAN ID BYTE[4] | CAN 数据 BYTE[8]
 * </pre>
 * CAN ID 的 4 个字节：bit31 通道号（0 CAN1，1 CAN2），bit30 帧类型（0 标准帧，1 扩展帧），
 * bit29 采集方式（0 原始数据，1 采集区间的平均值），bit28~0 总线 ID。
 * <p>
 * 采集哪些 ID、多久上传一次由终端参数 0x0100~0x0111 控制。数据本身是什么含义（转速、水温……）
 * 要看车型的 CAN 协议（比如 J1939），808 只负责搬运。
 */
public class CanBusData implements Jt808Body {

    private final String receiveTime;
    private final List<Item> items;

    public CanBusData(String receiveTime, List<Item> items) {
        this.receiveTime = receiveTime;
        this.items = items;
    }

    public static CanBusData decode(ByteBuf b, Jt808Header h) {
        int n = b.readUnsignedShort();
        String time = BcdUtils.readBcd(b, 5);
        List<Item> list = new ArrayList<>(n);
        for (int i = 0; i < n && b.readableBytes() >= 12; i++) {
            long id = b.readUnsignedInt();
            byte[] data = new byte[8];
            b.readBytes(data);
            list.add(new Item(id, data));
        }
        return new CanBusData(time, list);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        out.writeShort(items.size());
        BcdUtils.writeBcd(out, receiveTime, 5);
        for (Item item : items) {
            out.writeInt((int) item.rawId);
            out.writeBytes(item.data, 0, 8);
        }
    }

    /**
     * 接收时间 hhmmssSSSS（后 4 位是毫秒，BCD 原样保留）
     */
    public String getReceiveTime() {
        return receiveTime;
    }

    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return "CanBusData{time=" + receiveTime + ", items=" + items + '}';
    }

    public static final class Item {
        private final long rawId;
        private final byte[] data;

        public Item(long rawId, byte[] data) {
            this.rawId = rawId;
            this.data = data;
        }

        public static Item of(int channel, boolean extended, boolean average, int busId, byte[] data) {
            long id = (long) (channel & 1) << 31 | (extended ? 1L << 30 : 0) | (average ? 1L << 29 : 0)
                    | (busId & 0x1FFFFFFFL);
            return new Item(id, data);
        }

        public int getChannel() {
            return (int) (rawId >>> 31 & 1);
        }

        public boolean isExtended() {
            return (rawId >>> 30 & 1) == 1;
        }

        public boolean isAverage() {
            return (rawId >>> 29 & 1) == 1;
        }

        public int getBusId() {
            return (int) (rawId & 0x1FFFFFFF);
        }

        public long getRawId() {
            return rawId;
        }

        public byte[] getData() {
            return data;
        }

        @Override
        public String toString() {
            return String.format("CAN%d 0x%X=%s", getChannel() + 1, getBusId(),
                    ByteBufUtil.hexDump(data));
        }
    }
}
