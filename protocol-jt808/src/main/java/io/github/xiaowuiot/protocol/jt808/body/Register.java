package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.codec.util.ByteBufUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Version;
import io.netty.buffer.ByteBuf;

import static io.github.xiaowuiot.protocol.codec.util.ByteBufUtils.GBK;

/**
 * 终端注册 0x0100。
 * <pre>
 * 省域 ID WORD | 市县域 ID WORD | 制造商 ID BYTE[5/5/11] | 终端型号 BYTE[8/20/30] | 终端 ID BYTE[7/7/30]
 * | 车牌颜色 BYTE | 车牌号 STRING（剩余全部字节）
 * </pre>
 * 方括号里依次是 2011、2013、2019 版的长度。
 * <p>
 * 2011 和 2013 版的消息头一样，只能靠这条消息的长度区分：2013 版固定部分就有 37 字节，
 * 不到 37 字节的只可能是 2011 版（固定部分 25 字节，加上车牌也到不了 37）。
 * 解码时认出 2011 版会把消息头的版本改成 2011，解码器据此把整条连接标记为 2011 版。
 */
public class Register implements Jt808Body {

    /**
     * 2013 版固定部分的长度：2 + 2 + 5 + 20 + 7 + 1
     */
    private static final int FIXED_2013 = 37;

    private int province;
    private int city;
    private String makerId;
    private String model;
    private String terminalId;
    private int plateColor;
    private String plate;

    public static Register decode(ByteBuf body, Jt808Header header) {
        if (!header.isV2019() && body.readableBytes() < FIXED_2013) {
            header.setVersion(Jt808Version.V2011);
        }
        Register r = new Register();
        r.province = body.readUnsignedShort();
        r.city = body.readUnsignedShort();
        r.makerId = ByteBufUtils.readString(body, makerLength(header), GBK);
        r.model = ByteBufUtils.readString(body, modelLength(header), GBK);
        r.terminalId = ByteBufUtils.readString(body, terminalIdLength(header), GBK);
        r.plateColor = body.readUnsignedByte();
        r.plate = ByteBufUtils.readRemainingString(body, GBK);
        return r;
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeShort(province);
        out.writeShort(city);
        ByteBufUtils.writeString(out, makerId, makerLength(header), GBK);
        ByteBufUtils.writeString(out, model, modelLength(header), GBK);
        ByteBufUtils.writeString(out, terminalId, terminalIdLength(header), GBK);
        out.writeByte(plateColor);
        if (plate != null) {
            out.writeCharSequence(plate, GBK);
        }
    }

    private static int makerLength(Jt808Header h) {
        return h.isV2019() ? 11 : 5;
    }

    private static int modelLength(Jt808Header h) {
        switch (h.getVersion()) {
            case V2011:
                return 8;
            case V2019:
                return 30;
            default:
                return 20;
        }
    }

    private static int terminalIdLength(Jt808Header h) {
        return h.isV2019() ? 30 : 7;
    }

    public int getProvince() {
        return province;
    }

    public Register setProvince(int province) {
        this.province = province;
        return this;
    }

    public int getCity() {
        return city;
    }

    public Register setCity(int city) {
        this.city = city;
        return this;
    }

    public String getMakerId() {
        return makerId;
    }

    public Register setMakerId(String makerId) {
        this.makerId = makerId;
        return this;
    }

    public String getModel() {
        return model;
    }

    public Register setModel(String model) {
        this.model = model;
        return this;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public Register setTerminalId(String terminalId) {
        this.terminalId = terminalId;
        return this;
    }

    public int getPlateColor() {
        return plateColor;
    }

    public Register setPlateColor(int plateColor) {
        this.plateColor = plateColor;
        return this;
    }

    public String getPlate() {
        return plate;
    }

    public Register setPlate(String plate) {
        this.plate = plate;
        return this;
    }

    @Override
    public String toString() {
        return "Register{province=" + province + ", city=" + city + ", makerId=" + makerId + ", model=" + model
                + ", terminalId=" + terminalId + ", plateColor=" + plateColor + ", plate=" + plate + '}';
    }
}
