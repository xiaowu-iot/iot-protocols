package io.github.xiaowuiot.protocol.jt808.sensor;

import io.github.xiaowuiot.protocol.jt808.body.PassThrough;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 外接传感器的数据，通过 0x0900 数据上行透传上来。
 * <p>
 * 808 标准只规定了透传的"信封"，内容的格式由传感器厂家和平台约定，每家都不一样。
 * 这里给出三种常见传感器的<b>示例格式</b>（用户自定义透传类型 0xF1 ~ 0xF3），对接具体厂家时照着改解析器就行：
 * <pre>
 * 0xF1 胎压：  轮胎数 BYTE | 每个轮胎：位置 BYTE | 气压 WORD（kPa）| 温度 BYTE（℃，有符号）| 状态 BYTE（0 正常 1 低压 2 高压 3 高温 4 漏气）
 * 0xF2 载重：  重量 DWORD（kg）| 状态 BYTE（0 空载 1 半载 2 满载 3 超载）
 * 0xF3 温湿度：探头数 BYTE | 每个探头：编号 BYTE | 温度 WORD（0.1℃，有符号）| 湿度 BYTE（%）
 * </pre>
 */
public final class Sensors {

    public static final int TYPE_TIRE = 0xF1;
    public static final int TYPE_LOAD = 0xF2;
    public static final int TYPE_TEMPERATURE = 0xF3;

    private static final String[] TIRE_STATUS = {"正常", "低压", "高压", "高温", "漏气"};
    private static final String[] LOAD_STATUS = {"空载", "半载", "满载", "超载"};

    private Sensors() {
    }

    /**
     * 登记三种示例传感器的解析器
     */
    public static void register() {
        PassThrough.registerUplink(TYPE_TIRE, Sensors::parseTires);
        PassThrough.registerUplink(TYPE_LOAD, Sensors::parseLoad);
        PassThrough.registerUplink(TYPE_TEMPERATURE, Sensors::parseTemperature);
    }

    public static String name(int type) {
        switch (type) {
            case TYPE_TIRE:
                return "胎压";
            case TYPE_LOAD:
                return "载重";
            case TYPE_TEMPERATURE:
                return "温湿度";
            default:
                return String.format("透传 0x%02X", type);
        }
    }

    // ==================== 胎压 ====================

    public static final class Tire {
        public final int position;
        public final int kpa;
        public final int celsius;
        public final int status;

        public Tire(int position, int kpa, int celsius, int status) {
            this.position = position;
            this.kpa = kpa;
            this.celsius = celsius;
            this.status = status;
        }

        public String getStatusName() {
            return status >= 0 && status < TIRE_STATUS.length ? TIRE_STATUS[status] : "未知";
        }

        @Override
        public String toString() {
            return position + "号 " + kpa + "kPa " + celsius + "℃ " + getStatusName();
        }
    }

    public static List<Tire> parseTires(byte[] data) {
        ByteBuf b = Unpooled.wrappedBuffer(data);
        int n = b.readUnsignedByte();
        List<Tire> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Tire(b.readUnsignedByte(), b.readUnsignedShort(), b.readByte(), b.readUnsignedByte()));
        }
        return Collections.unmodifiableList(list);
    }

    public static byte[] encodeTires(List<Tire> tires) {
        ByteBuf b = Unpooled.buffer();
        try {
            b.writeByte(tires.size());
            for (Tire t : tires) {
                b.writeByte(t.position).writeShort(t.kpa).writeByte(t.celsius).writeByte(t.status);
            }
            return ByteBufUtil.getBytes(b);
        } finally {
            b.release();
        }
    }

    // ==================== 载重 ====================

    public static final class Load {
        public final long kg;
        public final int status;

        public Load(long kg, int status) {
            this.kg = kg;
            this.status = status;
        }

        public String getStatusName() {
            return status >= 0 && status < LOAD_STATUS.length ? LOAD_STATUS[status] : "未知";
        }

        @Override
        public String toString() {
            return kg + "kg " + getStatusName();
        }
    }

    public static Load parseLoad(byte[] data) {
        ByteBuf b = Unpooled.wrappedBuffer(data);
        return new Load(b.readUnsignedInt(), b.readUnsignedByte());
    }

    public static byte[] encodeLoad(Load l) {
        ByteBuf b = Unpooled.buffer(5);
        try {
            b.writeInt((int) l.kg).writeByte(l.status);
            return ByteBufUtil.getBytes(b);
        } finally {
            b.release();
        }
    }

    // ==================== 温湿度 ====================

    public static final class Probe {
        public final int no;
        public final double celsius;
        public final int humidity;

        public Probe(int no, double celsius, int humidity) {
            this.no = no;
            this.celsius = celsius;
            this.humidity = humidity;
        }

        @Override
        public String toString() {
            return no + "号 " + celsius + "℃ " + humidity + "%";
        }
    }

    public static List<Probe> parseTemperature(byte[] data) {
        ByteBuf b = Unpooled.wrappedBuffer(data);
        int n = b.readUnsignedByte();
        List<Probe> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Probe(b.readUnsignedByte(), b.readShort() / 10.0, b.readUnsignedByte()));
        }
        return Collections.unmodifiableList(list);
    }

    public static byte[] encodeTemperature(List<Probe> probes) {
        ByteBuf b = Unpooled.buffer();
        try {
            b.writeByte(probes.size());
            for (Probe p : probes) {
                b.writeByte(p.no).writeShort((int) Math.round(p.celsius * 10)).writeByte(p.humidity);
            }
            return ByteBufUtil.getBytes(b);
        } finally {
            b.release();
        }
    }
}
