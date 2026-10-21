package io.github.xiaowuiot.protocol.jt1078.media.rtmp;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMF0：RTMP 命令消息（connect、createStream、publish、onStatus……）的编码格式。
 * 只实现推流用得到的类型：数字、布尔、字符串、对象、null，解码时其余类型能跳过。
 */
final class Amf0 {

    static final int NUMBER = 0x00;
    static final int BOOLEAN = 0x01;
    static final int STRING = 0x02;
    static final int OBJECT = 0x03;
    static final int NULL = 0x05;
    static final int UNDEFINED = 0x06;
    static final int ECMA_ARRAY = 0x08;
    static final int OBJECT_END = 0x09;
    static final int STRICT_ARRAY = 0x0A;
    static final int DATE = 0x0B;
    static final int LONG_STRING = 0x0C;

    private Amf0() {
    }

    static void write(ByteBuf out, Object v) {
        if (v == null) {
            out.writeByte(NULL);
        } else if (v instanceof Number) {
            out.writeByte(NUMBER);
            out.writeDouble(((Number) v).doubleValue());
        } else if (v instanceof Boolean) {
            out.writeByte(BOOLEAN);
            out.writeByte((Boolean) v ? 1 : 0);
        } else if (v instanceof String) {
            byte[] b = ((String) v).getBytes(StandardCharsets.UTF_8);
            if (b.length > 0xFFFF) {
                out.writeByte(LONG_STRING);
                out.writeInt(b.length);
            } else {
                out.writeByte(STRING);
                out.writeShort(b.length);
            }
            out.writeBytes(b);
        } else if (v instanceof Map) {
            out.writeByte(OBJECT);
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                writeKey(out, String.valueOf(e.getKey()));
                write(out, e.getValue());
            }
            out.writeShort(0);
            out.writeByte(OBJECT_END);
        } else {
            throw new IllegalArgumentException("AMF0 不支持的类型：" + v.getClass());
        }
    }

    private static void writeKey(ByteBuf out, String k) {
        byte[] b = k.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.writeBytes(b);
    }

    /** 把整条消息解成值列表 */
    static List<Object> readAll(ByteBuf in) {
        List<Object> list = new ArrayList<>();
        while (in.isReadable()) {
            list.add(read(in));
        }
        return list;
    }

    static Object read(ByteBuf in) {
        int type = in.readUnsignedByte();
        switch (type) {
            case NUMBER:
                return in.readDouble();
            case BOOLEAN:
                return in.readByte() != 0;
            case STRING:
                return readString(in, in.readUnsignedShort());
            case LONG_STRING:
                return readString(in, in.readInt());
            case OBJECT:
                return readObject(in);
            case ECMA_ARRAY:
                in.skipBytes(4);
                return readObject(in);
            case STRICT_ARRAY: {
                int n = in.readInt();
                List<Object> list = new ArrayList<>(Math.min(n, 64));
                for (int i = 0; i < n; i++) {
                    list.add(read(in));
                }
                return list;
            }
            case DATE:
                in.skipBytes(10);
                return null;
            case NULL:
            case UNDEFINED:
                return null;
            default:
                throw new IllegalArgumentException("AMF0 不认识的类型 0x" + Integer.toHexString(type));
        }
    }

    private static Map<String, Object> readObject(ByteBuf in) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (in.isReadable()) {
            String k = readString(in, in.readUnsignedShort());
            if (k.isEmpty() && in.isReadable() && in.getUnsignedByte(in.readerIndex()) == OBJECT_END) {
                in.skipBytes(1);
                break;
            }
            m.put(k, read(in));
        }
        return m;
    }

    private static String readString(ByteBuf in, int len) {
        String s = in.toString(in.readerIndex(), len, StandardCharsets.UTF_8);
        in.skipBytes(len);
        return s;
    }
}
