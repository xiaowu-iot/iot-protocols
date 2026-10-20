package io.github.xiaowuiot.protocol.jt1078.inspect;

import io.github.xiaowuiot.protocol.codec.inspect.Field;
import io.github.xiaowuiot.protocol.codec.inspect.FieldReader;
import io.github.xiaowuiot.protocol.codec.inspect.Frame;
import io.github.xiaowuiot.protocol.codec.inspect.MessageInfo;
import io.github.xiaowuiot.protocol.codec.inspect.ProtocolInspector;
import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JT/T 1078 码流包解析器：找帧头 30 31 63 64，自动判断 SIM 卡号是 6 字节还是 10 字节，逐字段标注；
 * 视频包里能看到 H.264 / H.265 起始码的，列出 NAL 单元类型（SPS、PPS、I 帧切片……），排查花屏、黑屏时有用。
 * 1078 的信令（0x9101 实时音视频请求等）走 808 通道，在 808 里解析。
 */
public class Jt1078Inspector implements ProtocolInspector {

    private static final String[] TYPES = {"I 帧", "P 帧", "B 帧", "音频帧", "透传数据"};
    private static final String[] SUBS = {"原子包（不分包）", "分包的第一包", "分包的最后一包", "分包的中间包"};

    @Override
    public String id() {
        return "jt1078";
    }

    @Override
    public String name() {
        return "JT/T 1078";
    }

    @Override
    public String standard() {
        return "JT/T 1078-2016";
    }

    @Override
    public String description() {
        return "道路运输车辆视频通信：信令走 808（0x9101 请求实时视频等），音视频按类 RTP 包推到媒体服务器，帧头 30 31 63 64，没有转义和校验";
    }

    @Override
    public int detect(byte[] data) {
        int i = indexOfMagic(data, 0);
        if (i < 0) {
            return 0;
        }
        return simLength(data, i) > 0 ? 95 : 60;
    }

    private static int indexOfMagic(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if (d[i] == 0x30 && d[i + 1] == 0x31 && d[i + 2] == 0x63 && d[i + 3] == 0x64) {
                return i;
            }
        }
        return -1;
    }

    /**
     * SIM 卡号 6 字节还是 10 字节：类型和分包标记要合法，包长正好到数据末尾或接着下一个帧头。都不像返回 0。
     */
    static int simLength(byte[] d, int start) {
        for (int sl : new int[]{6, 10}) {
            int ti = start + 8 + sl + 1;
            if (ti >= d.length) {
                continue;
            }
            int type = (d[ti] & 0xFF) >> 4;
            int sub = d[ti] & 0x0F;
            if (type > Jt1078Packet.TYPE_PASSTHROUGH || sub > Jt1078Packet.MIDDLE) {
                continue;
            }
            int hl = Jt1078Packet.headerLength(sl, type);
            if (start + hl > d.length) {
                continue;
            }
            int bl = ((d[start + hl - 2] & 0xFF) << 8) | (d[start + hl - 1] & 0xFF);
            int end = start + hl + bl;
            if (bl <= 4096 && (end == d.length || (end + 4 <= d.length && indexOfMagic(d, end) == end))) {
                return sl;
            }
        }
        return 0;
    }

    @Override
    public List<Frame> inspect(byte[] data) {
        List<Frame> out = new ArrayList<>();
        int i = indexOfMagic(data, 0);
        while (i >= 0) {
            int next = indexOfMagic(data, i + 4);
            int sl = simLength(data, i);
            int end = next < 0 ? data.length : next;
            if (sl > 0) {
                int type = (data[i + 8 + sl + 1] & 0xFF) >> 4;
                int hl = Jt1078Packet.headerLength(sl, type);
                int bl = ((data[i + hl - 2] & 0xFF) << 8) | (data[i + hl - 1] & 0xFF);
                end = Math.min(data.length, i + hl + bl);
                next = indexOfMagic(data, end);
            }
            out.add(frame(Arrays.copyOfRange(data, i, end), sl));
            i = next;
        }
        return out;
    }

    private Frame frame(byte[] u, int sl) {
        String hex = hex(u);
        Frame fr = new Frame().setRawHex(hex).setHex(hex).setDirection("up").setMessageId("RTP");
        if (sl == 0) {
            sl = 6;
            fr.problem("按 6 字节和 10 字节 SIM 卡号都对不上（类型、分包标记或长度不合理），先按 6 字节显示");
        }
        FieldReader r = new FieldReader(u);
        try {
            int type = u.length > 8 + sl + 1 ? (u[8 + sl + 1] & 0xFF) >> 4 : 0;
            int hl = Jt1078Packet.headerLength(sl, type);
            Field head = new Field("包头", 0, Math.min(hl, u.length), r.hex(0, Math.min(hl, u.length)), hl + " 字节，SIM 卡号 " + sl + " 字节"
                    + (sl == 10 ? "（808-2019 终端）" : ""));
            fr.add(head);
            r.bytes(head, "帧头标识", 4, b -> "30 31 63 64");
            r.u8(head, "V / P / X / CC", v -> String.format("0x%02X：V=%d P=%d X=%d CC=%d", v, v >> 6, v >> 5 & 1, v >> 4 & 1, v & 0x0F)
                    + (v == 0x81 ? "" : "（一般固定 0x81）"));
            int mpt = r.peekU8(5);
            r.u8(head, "M / PT", v -> "M=" + (v >> 7) + (v >> 7 == 1 ? "（一帧的边界）" : "") + "，PT=" + (v & 0x7F) + " " + Jt1078Packet.payloadName(v & 0x7F));
            r.u16(head, "包序号", null);
            Field sim = r.bcd(head, "SIM 卡号（BCD）", sl);
            r.u8(head, "逻辑通道号", v -> v + channelName(v));
            r.u8(head, "数据类型 / 分包处理标记", v -> {
                int t = v >> 4;
                int s = v & 0x0F;
                return (t < TYPES.length ? TYPES[t] : "类型 " + t) + "，" + (s < SUBS.length ? SUBS[s] : "标记 " + s);
            });
            int sub = u[9 + sl] & 0x0F;
            String typeName = type < TYPES.length ? TYPES[type] : "未知";
            fr.setMessageName(Jt1078Packet.payloadName(mpt & 0x7F) + " " + typeName + "，通道 " + (u[8 + sl] & 0xFF) + "，" + (sub < SUBS.length ? SUBS[sub] : ""));
            if (type != Jt1078Packet.TYPE_PASSTHROUGH) {
                r.bytes(head, "时间戳（毫秒）", 8, b -> {
                    long v = 0;
                    for (byte x : b) {
                        v = v << 8 | (x & 0xFF);
                    }
                    return String.valueOf(v);
                });
            }
            if (type <= Jt1078Packet.TYPE_B) {
                r.u16(head, "与上一关键帧间隔（毫秒）", null);
                r.u16(head, "与上一帧间隔（毫秒）", null);
            }
            int bl = r.peekU16();
            Field lf = r.u16(head, "数据体长度", null);
            if (bl > Jt1078Packet.MAX_BODY) {
                lf.problem("标准规定不超过 950");
            }
            int actual = u.length - hl;
            if (actual != bl) {
                lf.problem("实际 " + Math.max(0, actual) + " 字节");
                fr.problem("数据体长度写的是 " + bl + "，实际 " + Math.max(0, actual) + " 字节");
            }
            int n = Math.max(0, Math.min(bl, actual));
            Field body = new Field("数据体", hl, n, r.hex(hl, Math.min(n, 64)) + (n > 64 ? "…" : ""), n + " 字节");
            fr.add(body);
            if (type <= Jt1078Packet.TYPE_B) {
                nals(u, hl, hl + n, mpt & 0x7F, body);
            }
            if (sim.getValue() != null && sim.getValue().matches("0+")) {
                fr.problem("SIM 卡号全是 0，终端可能没配置");
            }
        } catch (FieldReader.Truncated e) {
            fr.problem(e.getMessage());
        }
        return fr;
    }

    private static String channelName(int ch) {
        if (ch == 0) {
            return "（保留）";
        }
        if (ch <= 32) {
            return "（音视频通道）";
        }
        return ch == 36 ? "（驾驶员）" : ch == 37 ? "（车辆正前方）" : ch == 38 ? "（车前门）" : ch == 39 ? "（车厢前部）" : "";
    }

    /** 找 00 00 01 起始码，列出 NAL 单元类型 */
    private static void nals(byte[] u, int from, int to, int pt, Field body) {
        boolean h265 = pt == Jt1078Packet.PT_H265;
        int count = 0;
        for (int i = from; i + 3 < to && count < 20; i++) {
            if (u[i] == 0 && u[i + 1] == 0 && u[i + 2] == 1) {
                int h = u[i + 3] & 0xFF;
                int type = h265 ? (h >> 1) & 0x3F : h & 0x1F;
                int start = i > from && u[i - 1] == 0 ? i - 1 : i;
                body.add(new Field("NAL 单元", start, i + 4 - start, hex(Arrays.copyOfRange(u, start, i + 4)),
                        "type " + type + " " + (h265 ? h265Name(type) : h264Name(type))));
                count++;
                i += 3;
            }
        }
        if (count == 0) {
            body.value(body.getValue() + "，没有起始码（分包的中间部分）");
        }
    }

    private static String h264Name(int t) {
        switch (t) {
            case 1:
                return "非 IDR 切片（P/B 帧）";
            case 5:
                return "IDR 切片（关键帧）";
            case 6:
                return "SEI";
            case 7:
                return "SPS";
            case 8:
                return "PPS";
            case 9:
                return "AUD 分隔符";
            default:
                return "";
        }
    }

    private static String h265Name(int t) {
        if (t <= 9) {
            return "非关键帧切片";
        }
        if (t >= 16 && t <= 21) {
            return "IRAP 切片（关键帧）";
        }
        switch (t) {
            case 32:
                return "VPS";
            case 33:
                return "SPS";
            case 34:
                return "PPS";
            case 35:
                return "AUD 分隔符";
            case 39:
            case 40:
                return "SEI";
            default:
                return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02X", x & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public List<MessageInfo> messages() {
        List<MessageInfo> list = new ArrayList<>();
        list.add(new MessageInfo("RTP", "音视频码流包（H.264 / H.265 / G.711A / G.711U / ADPCMA / AAC）", "up", true, false));
        String[][] sig = {{"0x1003", "终端上传音视频属性", "up"}, {"0x1005", "终端上传乘客流量", "up"}, {"0x1205", "终端上传音视频资源列表", "up"},
                {"0x1206", "文件上传完成通知", "up"}, {"0x9003", "查询终端音视频属性", "down"}, {"0x9101", "实时音视频传输请求", "down"},
                {"0x9102", "音视频实时传输控制", "down"}, {"0x9105", "实时音视频传输状态通知", "down"}, {"0x9201", "平台下发远程录像回放请求", "down"},
                {"0x9202", "平台下发远程录像回放控制", "down"}, {"0x9205", "查询资源列表", "down"}, {"0x9206", "文件上传指令", "down"},
                {"0x9207", "文件上传控制", "down"}, {"0x9301", "云台旋转", "down"}, {"0x9302", "云台调整焦距控制", "down"}};
        List<String> done = Arrays.asList("0x1003", "0x1205", "0x9003", "0x9101", "0x9102", "0x9201", "0x9202", "0x9205");
        for (String[] s : sig) {
            list.add(new MessageInfo(s[0], s[1] + "（走 808 通道）", s[2], done.contains(s[0]), false));
        }
        return list;
    }
}
