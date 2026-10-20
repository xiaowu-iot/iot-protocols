package io.github.xiaowuiot.protocol.jt808;

import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.netty.buffer.ByteBuf;

/**
 * 808 消息头，支持 2011、2013、2019 版。
 * <pre>
 * 2011/2013：消息 ID(2) 消息体属性(2)               终端手机号 BCD[6]  流水号(2) [总包数(2) 包序号(2)]
 * 2019：     消息 ID(2) 消息体属性(2) 协议版本号(1) 终端手机号 BCD[10] 流水号(2) [总包数(2) 包序号(2)]
 * </pre>
 * 消息体属性：bit0~9 消息体长度，bit10~12 加密方式，bit13 是否分包，bit14 版本标识（2019 版为 1）。
 * <p>
 * 2011 和 2013 版的消息头一样，{@link #read} 读出来一律是 2013 版，是不是 2011 版由解码器根据连接上记录的版本修正，
 * 见 {@link Jt808Version}。
 */
public class Jt808Header {

    public static final int MAX_BODY_LENGTH = 0x03FF;

    private int msgId;
    private int bodyLength;
    private int encryption;
    private boolean subpackage;
    private Jt808Version version = Jt808Version.V2013;
    private int protocolVersion;
    private String phone;
    private int serialNo;
    private int totalPackets;
    private int packetNo;

    public Jt808Header() {
    }

    /**
     * 回复或下发给某个终端时用：版本、手机号都跟着终端走。
     */
    public static Jt808Header to(Jt808Header terminal, int msgId, int serialNo) {
        Jt808Header h = new Jt808Header();
        h.msgId = msgId;
        h.version = terminal.version;
        h.protocolVersion = terminal.protocolVersion;
        h.phone = terminal.phone;
        h.serialNo = serialNo;
        return h;
    }

    /**
     * 新建一个消息头，模拟器和测试用
     */
    public static Jt808Header of(Jt808Version version, String phone, int msgId, int serialNo) {
        Jt808Header h = new Jt808Header();
        h.msgId = msgId;
        h.version = version;
        h.protocolVersion = version == Jt808Version.V2019 ? 1 : 0;
        h.phone = phone;
        h.serialNo = serialNo;
        return h;
    }

    public static Jt808Header read(ByteBuf buf) {
        Jt808Header h = new Jt808Header();
        h.msgId = buf.readUnsignedShort();
        int attr = buf.readUnsignedShort();
        h.bodyLength = attr & MAX_BODY_LENGTH;
        h.encryption = (attr >> 10) & 0x07;
        h.subpackage = (attr >> 13 & 1) == 1;
        if ((attr >> 14 & 1) == 1) {
            h.version = Jt808Version.V2019;
            h.protocolVersion = buf.readUnsignedByte();
            h.phone = BcdUtils.readBcd(buf, 10);
        } else {
            h.version = Jt808Version.V2013;
            h.phone = BcdUtils.readBcd(buf, 6);
        }
        h.serialNo = buf.readUnsignedShort();
        if (h.subpackage) {
            h.totalPackets = buf.readUnsignedShort();
            h.packetNo = buf.readUnsignedShort();
        }
        return h;
    }

    public void write(ByteBuf buf) {
        if (bodyLength > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("消息体超过 1023 字节，需要分包：" + bodyLength);
        }
        boolean v19 = isV2019();
        buf.writeShort(msgId);
        int attr = bodyLength | (encryption & 0x07) << 10;
        if (subpackage) {
            attr |= 1 << 13;
        }
        if (v19) {
            attr |= 1 << 14;
        }
        buf.writeShort(attr);
        if (v19) {
            buf.writeByte(protocolVersion);
            BcdUtils.writeBcd(buf, phone, 10);
        } else {
            BcdUtils.writeBcd(buf, phone, 6);
        }
        buf.writeShort(serialNo);
        if (subpackage) {
            buf.writeShort(totalPackets);
            buf.writeShort(packetNo);
        }
    }

    public int headerLength() {
        return (isV2019() ? 17 : 12) + (subpackage ? 4 : 0);
    }

    /**
     * 设备号：手机号去掉前导 0。
     * <p>
     * 同一台终端从 2013 版升级到 2019 版后，手机号从 {@code 013800138000} 变成 {@code 00000000013800138000}，
     * 去掉前导 0 之后都是 {@code 13800138000}，平台上还是同一台设备。
     */
    public String deviceId() {
        int i = 0;
        while (i < phone.length() - 1 && phone.charAt(i) == '0') {
            i++;
        }
        return phone.substring(i);
    }

    public int getMsgId() {
        return msgId;
    }

    public void setMsgId(int msgId) {
        this.msgId = msgId;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }

    public int getEncryption() {
        return encryption;
    }

    public void setEncryption(int encryption) {
        this.encryption = encryption;
    }

    public boolean isSubpackage() {
        return subpackage;
    }

    public void setSubpackage(boolean subpackage) {
        this.subpackage = subpackage;
    }

    public Jt808Version getVersion() {
        return version;
    }

    public void setVersion(Jt808Version version) {
        this.version = version;
    }

    public boolean isV2019() {
        return version == Jt808Version.V2019;
    }

    public boolean isV2011() {
        return version == Jt808Version.V2011;
    }

    public void setV2019(boolean v2019) {
        this.version = v2019 ? Jt808Version.V2019 : Jt808Version.V2013;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public int getSerialNo() {
        return serialNo;
    }

    public void setSerialNo(int serialNo) {
        this.serialNo = serialNo;
    }

    public int getTotalPackets() {
        return totalPackets;
    }

    public void setTotalPackets(int totalPackets) {
        this.totalPackets = totalPackets;
    }

    public int getPacketNo() {
        return packetNo;
    }

    public void setPacketNo(int packetNo) {
        this.packetNo = packetNo;
    }

    @Override
    public String toString() {
        return String.format("0x%04X %d phone=%s serial=%d len=%d%s", msgId, version.year(),
                phone, serialNo, bodyLength, subpackage ? " packet=" + packetNo + "/" + totalPackets : "");
    }
}
