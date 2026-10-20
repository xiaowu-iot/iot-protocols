package io.github.xiaowuiot.protocol.jt808;

/**
 * 808 协议版本。
 * <p>
 * 2019 版能从消息头直接认出来（消息体属性 bit14 = 1，手机号 10 字节）；
 * 2011 和 2013 版的消息头完全一样，只能从注册消息 0x0100 的长度区分（2011 版终端型号 8 字节，2013 版 20 字节），
 * 认出来以后记在连接上，这条连接后面的消息都按 2011 版解析。
 * <p>
 * 两个版本的差异集中在几条消息上：注册 0x0100、驾驶员身份信息 0x0702、多媒体数据上传 0x0801。
 */
public enum Jt808Version {

    V2011(2011),
    V2013(2013),
    V2019(2019);

    private final int year;

    Jt808Version(int year) {
        this.year = year;
    }

    public int year() {
        return year;
    }

    public static Jt808Version ofYear(int year) {
        for (Jt808Version v : values()) {
            if (v.year == year) {
                return v;
            }
        }
        throw new IllegalArgumentException("不支持的 808 版本：" + year);
    }
}
