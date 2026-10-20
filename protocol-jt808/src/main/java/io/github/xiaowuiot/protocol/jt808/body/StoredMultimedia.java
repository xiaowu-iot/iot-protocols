package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 终端里存储的多媒体（照片、录音、录像）的检索和上传。
 * <pre>
 * 平台 ── 0x8802 存储多媒体数据检索 ──────────► 终端
 * 平台 ◄── 0x0802 检索应答（多媒体 ID 列表）── 终端
 * 平台 ── 0x8803 存储多媒体数据上传（按条件）─► 终端    终端随后用 0x0801 逐个上传
 * 平台 ── 0x8805 单条存储多媒体检索上传（按 ID）► 终端
 * 平台 ── 0x8804 录音开始 ────────────────────► 终端
 * </pre>
 * 多媒体类型：0 图像，1 音频，2 视频。事件项编码：0 平台下发指令，1 定时动作，2 抢劫报警触发，3 碰撞侧翻报警触发。
 */
public final class StoredMultimedia {

    private StoredMultimedia() {
    }

    /**
     * 0x8802 存储多媒体数据检索：多媒体类型 BYTE | 通道 ID BYTE（0 表示全部通道）| 事件项编码 BYTE
     * | 起始时间 BCD[6] | 结束时间 BCD[6]（全 0 表示不限）
     */
    public static final class Query implements Jt808Body {
        private final int type;
        private final int channel;
        private final int event;
        private final LocalDateTime start;
        private final LocalDateTime end;

        public Query(int type, int channel, int event, LocalDateTime start, LocalDateTime end) {
            this.type = type;
            this.channel = channel;
            this.event = event;
            this.start = start;
            this.end = end;
        }

        public static Query decode(ByteBuf b, Jt808Header h) {
            return new Query(b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(),
                    BcdUtils.readBcdTime(b), BcdUtils.readBcdTime(b));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(type).writeByte(channel).writeByte(event);
            BcdUtils.writeBcdTime(out, start);
            BcdUtils.writeBcdTime(out, end);
        }

        public int getType() {
            return type;
        }

        public int getChannel() {
            return channel;
        }

        public int getEvent() {
            return event;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }

        @Override
        public String toString() {
            return "StoredMediaQuery{type=" + type + ", channel=" + channel + ", event=" + event
                    + ", " + start + " ~ " + end + '}';
        }
    }

    /**
     * 0x0802 存储多媒体数据检索应答：应答流水号 WORD | 多媒体数据总项数 WORD
     * | 检索项：多媒体 ID DWORD | 多媒体类型 BYTE | 通道 ID BYTE | 事件项编码 BYTE | 位置基本信息 BYTE[28]
     */
    public static final class QueryResponse implements Jt808Body {
        private final int replySerialNo;
        private final List<Item> items;

        public QueryResponse(int replySerialNo, List<Item> items) {
            this.replySerialNo = replySerialNo;
            this.items = items;
        }

        public static QueryResponse decode(ByteBuf b, Jt808Header h) {
            int serial = b.readUnsignedShort();
            int n = b.readUnsignedShort();
            List<Item> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long id = b.readUnsignedInt();
                int type = b.readUnsignedByte();
                int channel = b.readUnsignedByte();
                int event = b.readUnsignedByte();
                Location loc = Location.decode(b.readSlice(28), h);
                list.add(new Item(id, type, channel, event, loc));
            }
            return new QueryResponse(serial, list);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeShort(replySerialNo);
            out.writeShort(items.size());
            for (Item item : items) {
                out.writeInt((int) item.mediaId).writeByte(item.type).writeByte(item.channel).writeByte(item.event);
                item.location.basic().encode(out, h);
            }
        }

        public int getReplySerialNo() {
            return replySerialNo;
        }

        public List<Item> getItems() {
            return Collections.unmodifiableList(items);
        }

        @Override
        public String toString() {
            return "StoredMediaQueryResponse{reply=" + replySerialNo + ", items=" + items + '}';
        }
    }

    public static final class Item {
        private final long mediaId;
        private final int type;
        private final int channel;
        private final int event;
        private final Location location;

        public Item(long mediaId, int type, int channel, int event, Location location) {
            this.mediaId = mediaId;
            this.type = type;
            this.channel = channel;
            this.event = event;
            this.location = location;
        }

        public long getMediaId() {
            return mediaId;
        }

        public int getType() {
            return type;
        }

        public int getChannel() {
            return channel;
        }

        public int getEvent() {
            return event;
        }

        public Location getLocation() {
            return location;
        }

        @Override
        public String toString() {
            return "{id=" + mediaId + ", type=" + type + ", channel=" + channel + ", event=" + event + '}';
        }
    }

    /**
     * 0x8803 存储多媒体数据上传：多媒体类型 BYTE | 通道 ID BYTE | 事件项编码 BYTE
     * | 起始时间 BCD[6] | 结束时间 BCD[6] | 删除标志 BYTE（0 保留，1 删除）
     */
    public static final class Upload implements Jt808Body {
        private final Query query;
        private final boolean delete;

        public Upload(Query query, boolean delete) {
            this.query = query;
            this.delete = delete;
        }

        public static Upload decode(ByteBuf b, Jt808Header h) {
            Query q = Query.decode(b, h);
            return new Upload(q, b.readUnsignedByte() == 1);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            query.encode(out, h);
            out.writeByte(delete ? 1 : 0);
        }

        public Query getQuery() {
            return query;
        }

        public boolean isDelete() {
            return delete;
        }

        @Override
        public String toString() {
            return "StoredMediaUpload{" + query + ", delete=" + delete + '}';
        }
    }

    /**
     * 0x8805 单条存储多媒体数据检索上传：多媒体 ID DWORD | 删除标志 BYTE
     */
    public static final class UploadOne implements Jt808Body {
        private final long mediaId;
        private final boolean delete;

        public UploadOne(long mediaId, boolean delete) {
            this.mediaId = mediaId;
            this.delete = delete;
        }

        public static UploadOne decode(ByteBuf b, Jt808Header h) {
            return new UploadOne(b.readUnsignedInt(), b.readUnsignedByte() == 1);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeInt((int) mediaId);
            out.writeByte(delete ? 1 : 0);
        }

        public long getMediaId() {
            return mediaId;
        }

        public boolean isDelete() {
            return delete;
        }

        @Override
        public String toString() {
            return "StoredMediaUploadOne{id=" + mediaId + ", delete=" + delete + '}';
        }
    }

    /**
     * 0x8804 录音开始命令：录音命令 BYTE（0 停止，1 开始）| 录音时间 WORD（秒，0 表示一直录）
     * | 保存标志 BYTE（0 实时上传，1 保存）| 音频采样率 BYTE（0 8K，1 11K，2 23K，3 32K）
     */
    public static final class Record implements Jt808Body {
        private final boolean start;
        private final int seconds;
        private final boolean save;
        private final int sampleRate;

        public Record(boolean start, int seconds, boolean save, int sampleRate) {
            this.start = start;
            this.seconds = seconds;
            this.save = save;
            this.sampleRate = sampleRate;
        }

        public static Record decode(ByteBuf b, Jt808Header h) {
            return new Record(b.readUnsignedByte() == 1, b.readUnsignedShort(), b.readUnsignedByte() == 1,
                    b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(start ? 1 : 0);
            out.writeShort(seconds);
            out.writeByte(save ? 1 : 0);
            out.writeByte(sampleRate);
        }

        public boolean isStart() {
            return start;
        }

        public int getSeconds() {
            return seconds;
        }

        public boolean isSave() {
            return save;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        @Override
        public String toString() {
            return "Record{" + (start ? "开始" : "停止") + ", " + seconds + "s, save=" + save
                    + ", sampleRate=" + sampleRate + '}';
        }
    }
}
