package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.github.xiaowuiot.protocol.codec.util.ByteBufUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static io.github.xiaowuiot.protocol.codec.util.ByteBufUtils.GBK;

/**
 * 驾驶员身份信息采集上报 0x0702。驾驶员插、拔从业资格证 IC 卡时终端主动上报；平台也可以用 0x8702（消息体为空）请求上报。
 * <pre>
 * 状态 BYTE（0x01 插卡 / 上班，0x02 拔卡 / 下班） | 时间 BCD[6] |
 * 以下字段只在插卡时有：
 * IC 卡读取结果 BYTE（0 成功 1 密钥认证未通过 2 卡被锁定 3 卡被拔出 4 数据校验错误） |
 * 以下字段只在读卡成功时有：
 * 姓名长度 BYTE | 姓名 STRING | 从业资格证编码 STRING[20] | 发证机构名称长度 BYTE | 发证机构名称 STRING |
 * 证件有效期 BCD[4]（YYYYMMDD） | 驾驶员身份证号 STRING[20]（2019 版新增）
 * </pre>
 * 字段是否存在取决于前面字段的值，这种"条件字段"是 808 里最容易解析错的结构之一。
 * <p>
 * 2011 版的格式完全不同，没有状态、时间和读卡结果，也没有有效期：
 * <pre>
 * 姓名长度 BYTE | 姓名 STRING | 身份证编码 STRING[20] | 从业资格证编码 STRING[40] | 发证机构名称长度 BYTE | 发证机构名称 STRING
 * </pre>
 * 2011 版解码后状态按插卡、读卡成功处理。
 */
public class DriverInfo implements Jt808Body {

    public static final int CARD_IN = 0x01;
    public static final int CARD_OUT = 0x02;
    public static final int READ_OK = 0;

    private final int status;
    private final LocalDateTime time;
    private final int readResult;
    private final String name;
    private final String certNo;
    private final String authority;
    private final LocalDate validUntil;
    private final String idCard;

    public DriverInfo(int status, LocalDateTime time, int readResult, String name, String certNo, String authority,
                      LocalDate validUntil, String idCard) {
        this.status = status;
        this.time = time;
        this.readResult = readResult;
        this.name = name;
        this.certNo = certNo;
        this.authority = authority;
        this.validUntil = validUntil;
        this.idCard = idCard;
    }

    public static DriverInfo cardOut(LocalDateTime time) {
        return new DriverInfo(CARD_OUT, time, 0, null, null, null, null, null);
    }

    public static DriverInfo decode(ByteBuf b, Jt808Header h) {
        if (h.isV2011()) {
            String name = ByteBufUtils.readByteLengthString(b, GBK);
            String idCard = ByteBufUtils.readString(b, 20, GBK);
            String cert = ByteBufUtils.readString(b, 40, GBK);
            String authority = ByteBufUtils.readByteLengthString(b, GBK);
            return new DriverInfo(CARD_IN, null, READ_OK, name, cert, authority, null, idCard);
        }
        int status = b.readUnsignedByte();
        LocalDateTime time = BcdUtils.readBcdTime(b);
        if (status != CARD_IN) {
            return cardOut(time);
        }
        int result = b.readUnsignedByte();
        if (result != READ_OK) {
            return new DriverInfo(status, time, result, null, null, null, null, null);
        }
        String name = ByteBufUtils.readByteLengthString(b, GBK);
        String cert = ByteBufUtils.readString(b, 20, GBK);
        String authority = ByteBufUtils.readByteLengthString(b, GBK);
        LocalDate valid = LocalDate.parse(BcdUtils.readBcd(b, 4), DateTimeFormatter.BASIC_ISO_DATE);
        String idCard = h.isV2019() && b.readableBytes() >= 20 ? ByteBufUtils.readString(b, 20, GBK) : null;
        return new DriverInfo(status, time, result, name, cert, authority, valid, idCard);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        if (h.isV2011()) {
            ByteBufUtils.writeByteLengthString(out, name, GBK);
            ByteBufUtils.writeString(out, idCard == null ? "" : idCard, 20, GBK);
            ByteBufUtils.writeString(out, certNo, 40, GBK);
            ByteBufUtils.writeByteLengthString(out, authority, GBK);
            return;
        }
        out.writeByte(status);
        BcdUtils.writeBcdTime(out, time);
        if (status != CARD_IN) {
            return;
        }
        out.writeByte(readResult);
        if (readResult != READ_OK) {
            return;
        }
        ByteBufUtils.writeByteLengthString(out, name, GBK);
        ByteBufUtils.writeString(out, certNo, 20, GBK);
        ByteBufUtils.writeByteLengthString(out, authority, GBK);
        BcdUtils.writeBcd(out, validUntil.format(DateTimeFormatter.BASIC_ISO_DATE), 4);
        if (h.isV2019()) {
            ByteBufUtils.writeString(out, idCard == null ? "" : idCard, 20, GBK);
        }
    }

    public int getStatus() {
        return status;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public int getReadResult() {
        return readResult;
    }

    public String getName() {
        return name;
    }

    public String getCertNo() {
        return certNo;
    }

    public String getAuthority() {
        return authority;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public String getIdCard() {
        return idCard;
    }

    @Override
    public String toString() {
        if (status != CARD_IN) {
            return "DriverInfo{拔卡, " + time + '}';
        }
        return "DriverInfo{插卡, " + time + ", 读卡结果 " + readResult
                + (readResult == READ_OK ? ", " + name + ", 资格证 " + certNo + ", 有效期至 " + validUntil : "") + '}';
    }
}
