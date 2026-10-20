package io.github.xiaowuiot.protocol.jt808.inspect;

import io.github.xiaowuiot.protocol.codec.inspect.BuildTemplate;
import io.github.xiaowuiot.protocol.codec.inspect.Field;
import io.github.xiaowuiot.protocol.codec.inspect.FieldReader;
import io.github.xiaowuiot.protocol.codec.inspect.Frame;
import io.github.xiaowuiot.protocol.codec.inspect.MessageInfo;
import io.github.xiaowuiot.protocol.codec.inspect.ObjectFields;
import io.github.xiaowuiot.protocol.codec.inspect.ProtocolInspector;
import io.github.xiaowuiot.protocol.codec.util.Checksums;
import io.github.xiaowuiot.protocol.jt808.Jt808Flags;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.Jt808Version;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.Auth;
import io.github.xiaowuiot.protocol.jt808.body.BodyDecoder;
import io.github.xiaowuiot.protocol.jt808.body.GeneralResponse;
import io.github.xiaowuiot.protocol.jt808.body.Location;
import io.github.xiaowuiot.protocol.jt808.body.Register;
import io.github.xiaowuiot.protocol.jt808.body.SimpleBodies;
import io.github.xiaowuiot.protocol.jt808.body.TextMessage;
import io.github.xiaowuiot.protocol.jt808.codec.BodyDecoders;
import io.github.xiaowuiot.protocol.jt808.codec.Jt808Codecs;
import io.github.xiaowuiot.protocol.jt808.codec.Jt808MessageEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JT/T 808 报文解析器：按 7E 切帧、反转义、校验，消息头逐字节标注；
 * 位置信息（0x0200 / 0x0201 / 0x0500）的基本信息和附加信息也逐字节标注，其他消息体用平台的解码器解出来再展开。
 */
public class Jt808Inspector implements ProtocolInspector {

    private static final Charset GBK = Charset.forName("GBK");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 附加信息 ID 的名称（JT/T 808-2019 表 27，加上主动安全的几个） */
    static final Map<Integer, String> EXTRA_NAMES = new HashMap<>();

    static {
        String[][] names = {{"01", "里程（1/10 km）"}, {"02", "油量（1/10 L）"}, {"03", "行驶记录仪速度（1/10 km/h）"}, {"04", "需要人工确认的报警事件 ID"},
                {"05", "胎压"}, {"06", "车厢温度"}, {"11", "超速报警附加信息"}, {"12", "进出区域/路线报警附加信息"}, {"13", "路段行驶时间不足/过长"},
                {"25", "扩展车辆信号状态位"}, {"2A", "IO 状态位"}, {"2B", "模拟量"}, {"30", "无线通信网络信号强度"}, {"31", "GNSS 定位卫星数"},
                {"64", "ADAS 高级驾驶辅助报警"}, {"65", "DSM 驾驶员状态监测报警"}, {"66", "TPMS 胎压监测报警"}, {"67", "BSD 盲区监测报警"},
                {"70", "激烈驾驶报警"}, {"E1", "厂家自定义"}};
        for (String[] n : names) {
            EXTRA_NAMES.put(Integer.parseInt(n[0], 16), n[1]);
        }
    }

    @Override
    public String id() {
        return "jt808";
    }

    @Override
    public String name() {
        return "JT/T 808";
    }

    @Override
    public String standard() {
        return "JT/T 808-2011 / 2013 / 2019";
    }

    @Override
    public String description() {
        return "道路运输车辆卫星定位终端通讯协议：注册鉴权、位置上报、报警、参数设置、指令下发，TCP 长连接，帧以 0x7E 开头结尾";
    }

    @Override
    public int detect(byte[] data) {
        List<byte[]> frames = split(data);
        if (frames.isEmpty()) {
            return 0;
        }
        boolean delimited = false;
        for (byte b : data) {
            if ((b & 0xFF) == 0x7E) {
                delimited = true;
                break;
            }
        }
        int ok = 0;
        for (byte[] f : frames) {
            byte[] u = unescape(f);
            if (u.length >= 13 && Checksums.bcc(Unpooled.wrappedBuffer(u), 0, u.length - 1) == (u[u.length - 1] & 0xFF)
                    && lengthMatches(u)) {
                ok++;
            }
        }
        if (ok > 0) {
            // 没有 7E 只是校验碰巧对上（比如 32960 的 BCC 和 808 算法一样），把握要低
            return delimited ? 95 : 30;
        }
        return delimited && (data[0] & 0xFF) == 0x7E ? 40 : 0;
    }

    /** 消息体属性里的长度和实际一致（头长度按 2019 版标志、分包标志算） */
    private static boolean lengthMatches(byte[] u) {
        int attr = ((u[2] & 0xFF) << 8) | (u[3] & 0xFF);
        int head = ((attr >> 14) & 1) == 1 ? 17 : 12;
        if (((attr >> 13) & 1) == 1) {
            head += 4;
        }
        return u.length == head + (attr & 0x3FF) + 1;
    }

    @Override
    public List<Frame> inspect(byte[] data) {
        List<Frame> out = new ArrayList<>();
        for (byte[] raw : split(data)) {
            out.add(frame(raw));
        }
        return out;
    }

    /** 按 7E 切出帧（不含 7E）；整段没有 7E 时当成一帧（有人只贴了中间部分） */
    static List<byte[]> split(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) == 0x7E) {
                if (start >= 0 && i - start > 1) {
                    list.add(Arrays.copyOfRange(data, start + 1, i));
                    start = -1;
                } else {
                    start = i;
                }
            }
        }
        if (list.isEmpty() && data.length > 0 && start < 0) {
            list.add(data);
        } else if (list.isEmpty() && start >= 0 && data.length - start > 1) {
            list.add(Arrays.copyOfRange(data, start + 1, data.length));
        }
        return list;
    }

    /** 7D 02 → 7E，7D 01 → 7D */
    static byte[] unescape(byte[] f) {
        ByteBuf out = Unpooled.buffer(f.length);
        for (int i = 0; i < f.length; i++) {
            int b = f[i] & 0xFF;
            if (b == 0x7D && i + 1 < f.length) {
                int n = f[++i] & 0xFF;
                out.writeByte(n == 0x02 ? 0x7E : n == 0x01 ? 0x7D : n);
            } else {
                out.writeByte(b);
            }
        }
        return ByteBufUtil.getBytes(out);
    }

    private Frame frame(byte[] raw) {
        byte[] u = unescape(raw);
        Frame fr = new Frame().setRawHex("7E" + ByteBufUtil.hexDump(raw).toUpperCase() + "7E").setHex(ByteBufUtil.hexDump(u).toUpperCase());
        if (raw.length != u.length) {
            fr.note("有 " + (raw.length - u.length) + " 处转义（7D 01 / 7D 02），下面的字节位置按反转义之后算");
        }
        FieldReader r = new FieldReader(u, 0, Math.max(0, u.length - 1));
        try {
            Field head = r.group(null, "消息头", 0, "");
            fr.add(head);
            int msgId = r.peekU16();
            r.u16(head, "消息 ID", v -> String.format("0x%04X %s", v, MessageId.name(v)));
            fr.setMessageId(String.format("0x%04X", msgId)).setMessageName(MessageId.name(msgId)).setDirection(msgId >= 0x8000 ? "down" : "up");
            int attr = r.peekU16();
            int bodyLen = attr & 0x3FF;
            boolean sub = (attr >> 13 & 1) == 1;
            boolean v19 = (attr >> 14 & 1) == 1;
            Field af = r.u16(head, "消息体属性", v -> String.format("0x%04X", v));
            af.add(new Field("消息体长度（bit0~9）", af.getOffset(), 2, "", String.valueOf(bodyLen)));
            af.add(new Field("加密方式（bit10~12）", af.getOffset(), 2, "", (attr >> 10 & 7) == 0 ? "不加密" : (attr >> 10 & 7) == 1 ? "RSA" : "保留"));
            af.add(new Field("分包（bit13）", af.getOffset(), 2, "", sub ? "是" : "否"));
            af.add(new Field("版本标识（bit14）", af.getOffset(), 2, "", v19 ? "1：2019 版" : "0：2011 / 2013 版"));
            if (v19) {
                r.u8(head, "协议版本号", v -> String.valueOf(v));
                r.bcd(head, "终端手机号（BCD 10 字节）", 10);
            } else {
                r.bcd(head, "终端手机号（BCD 6 字节）", 6);
            }
            r.u16(head, "消息流水号", null);
            if (sub) {
                r.u16(head, "总包数", null);
                r.u16(head, "包序号", null);
            }
            int headLen = r.position();
            replaceGroup(fr, head, 0, headLen, r);

            int actualBody = u.length - 1 - headLen;
            if (actualBody != bodyLen) {
                fr.problem("消息体属性里的长度是 " + bodyLen + "，实际是 " + Math.max(0, actualBody) + " 字节");
            }
            Field body = new Field("消息体", headLen, Math.max(0, actualBody), r.hex(headLen, Math.max(0, actualBody)),
                    actualBody + " 字节");
            fr.add(body);
            if (sub) {
                body.value(actualBody + " 字节（分包，要等所有包到齐才能解析）");
            } else if (actualBody > 0) {
                Jt808Header header = Jt808Header.read(Unpooled.wrappedBuffer(u, 0, headLen));
                body(fr, body, u, headLen, headLen + actualBody, header);
            }

            int expected = u[u.length - 1] & 0xFF;
            int actual = Checksums.bcc(Unpooled.wrappedBuffer(u), 0, u.length - 1);
            Field cs = new Field("校验码", u.length - 1, 1, String.format("%02X", expected), String.format("0x%02X", expected));
            if (expected != actual) {
                cs.problem("校验不对，按消息头和消息体异或算出来是 " + String.format("0x%02X", actual));
                fr.problem("校验码不对：报文里是 " + String.format("%02X", expected) + "，应该是 " + String.format("%02X", actual));
            } else {
                cs.value(String.format("0x%02X（正确）", expected));
            }
            fr.add(cs);
        } catch (FieldReader.Truncated e) {
            fr.problem(e.getMessage());
        }
        return fr;
    }

    /** 消息头这个分组建的时候还不知道长度，读完再换成有长度和十六进制的 */
    private static void replaceGroup(Frame fr, Field head, int start, int len, FieldReader r) {
        Field h = new Field(head.getName(), start, len, r.hex(start, len), len + " 字节");
        head.getChildren().forEach(h::add);
        fr.getFields().set(fr.getFields().indexOf(head), h);
    }

    private void body(Frame fr, Field body, byte[] u, int from, int to, Jt808Header header) {
        int id = header.getMsgId();
        if (id == MessageId.LOCATION || id == MessageId.LOCATION_QUERY_RESPONSE || id == 0x0500) {
            FieldReader r = new FieldReader(u, from, to);
            if (id != MessageId.LOCATION) {
                r.u16(body, "应答流水号", null);
            }
            location(r, body);
            return;
        }
        BodyDecoder<?> decoder = BodyDecoders.get(id);
        if (decoder == null) {
            body.value((to - from) + " 字节（平台还没有这条消息的解析器）");
            fr.problem("平台还没有实现 " + String.format("0x%04X", id) + " 的消息体解析");
            return;
        }
        try {
            Object obj = decoder.decode(Unpooled.wrappedBuffer(u, from, to - from), header);
            Field tree = ObjectFields.of("消息体", obj);
            tree.getChildren().forEach(body::add);
            if (tree.getValue() != null && body.getChildren().isEmpty()) {
                body.value(tree.getValue());
            }
        } catch (RuntimeException e) {
            body.problem("按标准解析失败：" + e.getMessage());
            fr.problem("消息体和标准对不上（多半是厂家固件和文档不一致）：" + e);
        }
    }

    /** 位置基本信息 28 字节 + 附加信息 */
    static void location(FieldReader r, Field parent) {
        long alarm = r.peekU32();
        Field a = r.u32(parent, "报警标志", v -> String.format("0x%08X", v));
        List<String> an = Jt808Flags.alarmNames(alarm);
        a.value(String.format("0x%08X", alarm) + (an.isEmpty() ? "（无报警）" : "：" + String.join("、", an)));
        long status = r.peekU32();
        Field s = r.u32(parent, "状态", v -> String.format("0x%08X", v));
        s.value(String.format("0x%08X：", status) + String.join("、", Jt808Flags.statusNames(status)));
        r.u32(parent, "纬度（1/10^6 度）", v -> String.format("%.6f", v / 1e6));
        r.u32(parent, "经度（1/10^6 度）", v -> String.format("%.6f", v / 1e6));
        r.u16(parent, "高程（米）", null);
        r.u16(parent, "速度（1/10 km/h）", v -> String.format("%.1f km/h", v / 10.0));
        r.u16(parent, "方向（度）", null);
        r.bcd(parent, "时间（BCD，YYMMDDhhmmss）", 6);
        int n = 0;
        while (r.remaining() >= 2) {
            int start = r.position();
            int eid = r.peekU8(start);
            int len = r.peekU8(start + 1);
            String name = EXTRA_NAMES.getOrDefault(eid, eid >= 0xE0 ? "厂家自定义" : "未知附加信息");
            Field ex = r.group(parent, String.format("附加信息 0x%02X %s", eid, name), 2 + len, "");
            r.u8(ex, "附加信息 ID", v -> String.format("0x%02X", v));
            r.u8(ex, "长度", null);
            if (r.remaining() < len) {
                ex.problem("长度写的是 " + len + "，后面只剩 " + r.remaining() + " 字节");
                throw new FieldReader.Truncated("附加信息 " + String.format("0x%02X", eid));
            }
            Field v = r.bytes(ex, "值", len, b -> extraValue(eid, b));
            ex.value(v.getValue());
            n++;
        }
        if (n > 0) {
            parent.value(parent.getValue() + "，附加信息 " + n + " 项");
        }
    }

    private static String extraValue(int id, byte[] b) {
        long v = 0;
        if (b.length <= 4) {
            for (byte x : b) {
                v = v << 8 | (x & 0xFF);
            }
        }
        switch (id) {
            case 0x01:
                return String.format("%.1f km", v / 10.0);
            case 0x02:
                return String.format("%.1f L", v / 10.0);
            case 0x03:
                return String.format("%.1f km/h", v / 10.0);
            case 0x30:
                return v + "（信号强度）";
            case 0x31:
                return v + " 颗";
            default:
                return b.length <= 4 ? String.valueOf(v) : ByteBufUtil.hexDump(b).toUpperCase();
        }
    }

    // ---------------------------------------------------------------- 消息清单

    @Override
    public List<MessageInfo> messages() {
        Map<Integer, String> ids = new TreeMap<>();
        for (java.lang.reflect.Field f : MessageId.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                try {
                    int id = f.getInt(null);
                    ids.put(id, MessageId.name(id));
                } catch (IllegalAccessException e) {
                    // 忽略
                }
            }
        }
        List<String> buildable = new ArrayList<>();
        templates().forEach(t -> buildable.add(t.getMessageId()));
        List<MessageInfo> list = new ArrayList<>();
        for (Map.Entry<Integer, String> e : ids.entrySet()) {
            int id = e.getKey();
            boolean up = id < 0x8000;
            String hex = String.format("0x%04X", id);
            // 上行看有没有解码器；下行平台用消息体类编码，能出现在清单里的都已实现
            boolean impl = !up || BodyDecoders.get(id) != null;
            list.add(new MessageInfo(hex, e.getValue(), up ? "up" : "down", impl, buildable.contains(hex)));
        }
        return list;
    }

    // ---------------------------------------------------------------- 构造

    @Override
    public List<BuildTemplate> templates() {
        List<BuildTemplate> t = new ArrayList<>();
        t.add(header(new BuildTemplate("0x0002", "终端心跳", "up")));
        t.add(header(new BuildTemplate("0x0100", "终端注册", "up"))
                .input("province", "省域 ID", "number", "44", "GB/T 2260 前两位")
                .input("city", "市县域 ID", "number", "300", null)
                .input("makerId", "制造商 ID", "text", "70111", "2019 版 11 字节，之前 5 字节")
                .input("model", "终端型号", "text", "JT-G7", null)
                .input("terminalId", "终端 ID", "text", "G7A0001", null)
                .input("plateColor", "车牌颜色", "select:0|1|2|3|4|5|9", "2", "0 未上牌、1 蓝、2 黄、3 黑、4 白、5 绿、9 其他")
                .input("plate", "车牌", "text", "粤B12345", null));
        t.add(header(new BuildTemplate("0x0102", "终端鉴权", "up")).input("authCode", "鉴权码", "text", "ABC123", "注册应答里平台给的"));
        t.add(header(new BuildTemplate("0x0200", "位置信息汇报", "up"))
                .input("alarm", "报警标志（十六进制）", "hex", "00000000", "比如 00000002 超速")
                .input("status", "状态（十六进制）", "hex", "00000003", "bit0 ACC 开，bit1 定位")
                .input("lat", "纬度", "number", "22.543096", null).input("lng", "经度", "number", "114.057865", null)
                .input("altitude", "高程（米）", "number", "30", null).input("speed", "速度（km/h）", "number", "45.5", null)
                .input("direction", "方向（度）", "number", "90", null).input("time", "时间", "time", "", "空着用当前时间")
                .input("mileage", "里程（km，附加信息 0x01）", "number", "12345.6", "空着不带"));
        t.add(header(new BuildTemplate("0x8001", "平台通用应答", "down"))
                .input("replySerial", "应答流水号", "number", "1", null).input("replyId", "应答 ID（十六进制）", "hex", "0200", null)
                .input("result", "结果", "select:0|1|2|3|4", "0", "0 成功、1 失败、2 消息有误、3 不支持、4 报警确认"));
        t.add(header(new BuildTemplate("0x8300", "文本信息下发", "down"))
                .input("flag", "标志（十六进制）", "hex", "0C", "bit2 终端显示器显示、bit3 TTS 播读")
                .input("text", "文本", "text", "请注意安全驾驶", null));
        t.add(header(new BuildTemplate("0x8201", "位置信息查询", "down")));
        t.add(header(new BuildTemplate("0x8202", "临时位置跟踪控制", "down"))
                .input("interval", "间隔（秒）", "number", "5", "0 停止跟踪").input("valid", "有效期（秒）", "number", "300", null));
        t.add(header(new BuildTemplate("0x8104", "查询终端参数", "down")));
        t.add(header(new BuildTemplate("0x8107", "查询终端属性", "down")));
        return t;
    }

    private static BuildTemplate header(BuildTemplate t) {
        return t.input("version", "协议版本", "select:2019|2013|2011", "2019", null)
                .input("phone", "终端手机号", "text", "13800138000", null)
                .input("serial", "流水号", "number", "1", null);
    }

    @Override
    public byte[] build(String messageId, Map<String, String> v) {
        int id = Integer.parseInt(messageId.replace("0x", "").replace("0X", ""), 16);
        Jt808Version version = Jt808Version.ofYear(Integer.parseInt(v.getOrDefault("version", "2019")));
        Jt808Header h = Jt808Header.of(version, v.getOrDefault("phone", "13800138000"), id, num(v, "serial", 1));
        Object body;
        switch (id) {
            case 0x0002:
            case 0x8201:
            case 0x8104:
            case 0x8107:
                body = null;
                break;
            case 0x0100:
                body = new Register().setProvince(num(v, "province", 44)).setCity(num(v, "city", 300)).setMakerId(v.get("makerId"))
                        .setModel(v.get("model")).setTerminalId(v.get("terminalId")).setPlateColor(num(v, "plateColor", 2)).setPlate(v.get("plate"));
                break;
            case 0x0102:
                body = new Auth(v.getOrDefault("authCode", ""));
                break;
            case 0x0200:
                Location l = new Location().setAlarm(Long.parseLong(v.getOrDefault("alarm", "0"), 16))
                        .setStatus(Long.parseLong(v.getOrDefault("status", "3"), 16))
                        .setLatitude(dbl(v, "lat")).setLongitude(dbl(v, "lng")).setAltitude(num(v, "altitude", 0))
                        .setSpeed(dbl(v, "speed")).setDirection(num(v, "direction", 0))
                        .setTime(blank(v.get("time")) ? LocalDateTime.now().withNano(0) : LocalDateTime.parse(v.get("time").trim(), TIME));
                if (!blank(v.get("mileage"))) {
                    l.putExtra(0x01, Math.round(Double.parseDouble(v.get("mileage")) * 10));
                }
                body = l;
                break;
            case 0x8001:
                body = new GeneralResponse(num(v, "replySerial", 0), Integer.parseInt(v.getOrDefault("replyId", "0200"), 16), num(v, "result", 0));
                break;
            case 0x8300:
                body = new TextMessage(Integer.parseInt(v.getOrDefault("flag", "0C"), 16), v.getOrDefault("text", ""));
                break;
            case 0x8202:
                body = new SimpleBodies.TrackingControl(num(v, "interval", 5), num(v, "valid", 300));
                break;
            default:
                throw new IllegalArgumentException("还没有 " + messageId + " 的构造模板");
        }
        ByteBuf frame = Unpooled.buffer();
        Jt808MessageEncoder.encode(new Jt808Message(h, body), frame);
        ByteBuf escaped = Unpooled.buffer();
        escaped.writeByte(0x7E);
        Jt808Codecs.ESCAPER.escape(frame, escaped);
        escaped.writeByte(0x7E);
        return ByteBufUtil.getBytes(escaped);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int num(Map<String, String> v, String k, int d) {
        String s = v.get(k);
        return blank(s) ? d : (int) Double.parseDouble(s.trim());
    }

    private static double dbl(Map<String, String> v, String k) {
        String s = v.get(k);
        return blank(s) ? 0 : Double.parseDouble(s.trim());
    }
}
