package io.github.xiaowuiot.protocol.codec.inspect;

import java.nio.charset.Charset;
import java.util.function.Function;

/**
 * 边读边记字段位置：报文解析器用它按顺序读字节，每读一个字段就生成一个带 offset / 长度 / 十六进制的 {@link Field}。
 * 字节不够时抛 {@link Truncated}，解析器捕获后把"报文不完整"记成问题。
 */
public class FieldReader {

    /** 读到这里字节不够了 */
    public static class Truncated extends RuntimeException {
        public Truncated(String field) {
            super(field + "：字节不够，报文不完整");
        }
    }

    private final byte[] data;
    private final int base;
    private final int end;
    private int pos;

    /**
     * @param data  整帧（反转义后）
     * @param start 从哪开始读
     * @param end   读到哪（不含）
     */
    public FieldReader(byte[] data, int start, int end) {
        this.data = data;
        this.base = start;
        this.pos = start;
        this.end = Math.min(end, data.length);
    }

    public FieldReader(byte[] data) {
        this(data, 0, data.length);
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return end - pos;
    }

    public void position(int p) {
        this.pos = p;
    }

    private void need(String name, int n) {
        if (pos + n > end) {
            throw new Truncated(name);
        }
    }

    public int peekU8(int at) {
        return data[at] & 0xFF;
    }

    public Field u8(Field parent, String name, Function<Integer, String> fmt) {
        need(name, 1);
        int v = data[pos] & 0xFF;
        return add(parent, name, 1, fmt == null ? String.valueOf(v) : fmt.apply(v));
    }

    public Field u16(Field parent, String name, Function<Integer, String> fmt) {
        need(name, 2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        return add(parent, name, 2, fmt == null ? String.valueOf(v) : fmt.apply(v));
    }

    public Field u32(Field parent, String name, Function<Long, String> fmt) {
        need(name, 4);
        long v = ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        return add(parent, name, 4, fmt == null ? String.valueOf(v) : fmt.apply(v));
    }

    /** 读出数值，不前进（先看值再决定怎么解释时用） */
    public int peekU16() {
        need("", 2);
        return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    }

    public long peekU32() {
        need("", 4);
        return ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
    }

    public Field bcd(Field parent, String name, int n) {
        need(name, n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X", data[pos + i] & 0xFF));
        }
        return add(parent, name, n, sb.toString());
    }

    public Field string(Field parent, String name, int n, Charset cs) {
        need(name, n);
        int len = n;
        while (len > 0 && data[pos + len - 1] == 0) {
            len--;
        }
        return add(parent, name, n, new String(data, pos, len, cs).trim());
    }

    public Field bytes(Field parent, String name, int n, Function<byte[], String> fmt) {
        need(name, n);
        byte[] b = new byte[n];
        System.arraycopy(data, pos, b, 0, n);
        return add(parent, name, n, fmt == null ? "" : fmt.apply(b));
    }

    /** 一段区域作为父字段，子字段由调用方继续读 */
    public Field group(Field parent, String name, int n, String value) {
        Field f = new Field(name, pos, n, hex(pos, Math.min(n, end - pos)), value);
        if (parent != null) {
            parent.add(f);
        }
        return f;
    }

    private Field add(Field parent, String name, int n, String value) {
        Field f = new Field(name, pos, n, hex(pos, n), value);
        pos += n;
        if (parent != null) {
            parent.add(f);
        }
        return f;
    }

    public String hex(int from, int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n && from + i < data.length; i++) {
            sb.append(String.format("%02X", data[from + i] & 0xFF));
        }
        return sb.toString();
    }

    public int base() {
        return base;
    }
}
