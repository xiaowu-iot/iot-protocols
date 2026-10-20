package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 查询区域或线路数据（2019 版新增）：平台用 0x8608 查询终端里存了哪些围栏，终端用 0x0608 应答。
 * <p>
 * 查询类型：1 圆形，2 矩形，3 多边形，4 路线。
 */
public final class AreaQuery {

    public static final int CIRCLE = 1;
    public static final int RECTANGLE = 2;
    public static final int POLYGON = 3;
    public static final int ROUTE = 4;

    private AreaQuery() {
    }

    /**
     * 0x8608：查询类型 BYTE | 要查询的 ID 数量 DWORD（0 表示查询全部）| ID DWORD × 数量
     */
    public static final class Request implements Jt808Body {
        private final int type;
        private final List<Long> ids;

        public Request(int type, List<Long> ids) {
            this.type = type;
            this.ids = ids;
        }

        public static Request decode(ByteBuf b, Jt808Header h) {
            int type = b.readUnsignedByte();
            long n = b.readUnsignedInt();
            List<Long> ids = new ArrayList<>();
            for (long i = 0; i < n && b.readableBytes() >= 4; i++) {
                ids.add(b.readUnsignedInt());
            }
            return new Request(type, ids);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(type);
            out.writeInt(ids.size());
            for (Long id : ids) {
                out.writeInt((int) id.longValue());
            }
        }

        public int getType() {
            return type;
        }

        public List<Long> getIds() {
            return Collections.unmodifiableList(ids);
        }

        @Override
        public String toString() {
            return "AreaQuery{type=" + type + ", ids=" + ids + '}';
        }
    }

    /**
     * 0x0608：查询类型 BYTE | 数量 DWORD | 区域或线路数据。
     * <p>
     * 数据部分的格式和设置区域 0x8600 / 0x8602 / 0x8604、设置路线 0x8606 的消息体一样，按查询类型交给对应的解码器解析。
     * 解析失败（厂家实现和标准对不上）时保留原始字节。
     */
    public static final class Response implements Jt808Body {
        private final int type;
        private final long count;
        private final Object data;

        public Response(int type, long count, Object data) {
            this.type = type;
            this.count = count;
            this.data = data;
        }

        public static Response decode(ByteBuf b, Jt808Header h) {
            int type = b.readUnsignedByte();
            long count = b.readUnsignedInt();
            int start = b.readerIndex();
            Object data;
            try {
                data = decodeData(type, b, h);
            } catch (RuntimeException e) {
                data = ByteBufUtil.getBytes(b.readerIndex(start));
            }
            return new Response(type, count, data);
        }

        private static Object decodeData(int type, ByteBuf b, Jt808Header h) {
            switch (type) {
                case CIRCLE:
                    return CircleArea.decode(b, h);
                case RECTANGLE:
                    return RectangleArea.decode(b, h);
                case POLYGON:
                    return PolygonArea.decode(b, h);
                case ROUTE:
                    return Route.decode(b, h);
                default:
                    return ByteBufUtil.getBytes(b);
            }
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(type);
            out.writeInt((int) count);
            if (data instanceof Jt808Body) {
                ((Jt808Body) data).encode(out, h);
            } else if (data instanceof byte[]) {
                out.writeBytes((byte[]) data);
            }
        }

        public int getType() {
            return type;
        }

        public long getCount() {
            return count;
        }

        /**
         * 解析成功时是 {@link CircleArea} 等对象，否则是原始 byte[]
         */
        public Object getData() {
            return data;
        }

        @Override
        public String toString() {
            return "AreaQueryResponse{type=" + type + ", count=" + count + ", data=" + data + '}';
        }
    }
}
