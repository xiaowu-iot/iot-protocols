package io.github.xiaowuiot.protocol.codec.inspect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 报文解析器的注册表：协议模块在 META-INF/services 里登记自己的 {@link ProtocolInspector}，
 * 引了哪些协议模块，这里就有哪些协议。自动识别、十六进制文本的清洗也放在这里，各个产品直接用。
 */
public class ProtocolInspectors {

    private final Map<String, ProtocolInspector> byId = new LinkedHashMap<>();

    public ProtocolInspectors(List<ProtocolInspector> list) {
        for (ProtocolInspector p : list) {
            byId.put(p.id(), p);
        }
    }

    /** 从 classpath 加载全部登记过的解析器 */
    public static ProtocolInspectors load() {
        List<ProtocolInspector> list = new ArrayList<>();
        ServiceLoader.load(ProtocolInspector.class).forEach(list::add);
        return new ProtocolInspectors(list);
    }

    public List<ProtocolInspector> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public ProtocolInspector get(String id) {
        ProtocolInspector p = byId.get(id);
        if (p == null) {
            throw new IllegalArgumentException("没有这个协议：" + id);
        }
        return p;
    }

    /** 自动识别结果：协议和把握，按把握从高到低 */
    public static final class Candidate {
        private final String id;
        private final String name;
        private final int score;

        Candidate(String id, String name, int score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getScore() {
            return score;
        }
    }

    public List<Candidate> detect(byte[] data) {
        List<Candidate> list = new ArrayList<>();
        for (ProtocolInspector p : byId.values()) {
            int s;
            try {
                s = p.detect(data);
            } catch (RuntimeException e) {
                s = 0;
            }
            if (s > 0) {
                list.add(new Candidate(p.id(), p.name(), s));
            }
        }
        list.sort(Comparator.comparingInt(Candidate::getScore).reversed());
        return list;
    }

    /**
     * 把粘贴进来的文本转成字节：允许空格、换行、逗号、0x 前缀、抓包工具的偏移列（每行开头 "0000 " 这种）。
     */
    public static byte[] parseHex(String text) {
        if (text == null) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            // Wireshark / 串口助手的偏移列："0000  7E 02 00 ..."，偏移后面至少两个空格或冒号
            l = l.replaceFirst("^[0-9a-fA-F]{4,8}(:|\\s{2,})", "");
            l = l.replaceAll("(?i)0x", "");
            sb.append(l.replaceAll("[\\s,;:\\-]", ""));
        }
        String s = sb.toString();
        if (!s.matches("[0-9a-fA-F]*")) {
            throw new IllegalArgumentException("只能粘贴十六进制（0-9、A-F），可以带空格和换行");
        }
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("十六进制字符个数是奇数（" + s.length() + " 个），少粘了半个字节");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
