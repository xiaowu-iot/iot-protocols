package io.github.xiaowuiot.protocol.jt1078.media.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * 一条 RTMP 消息，以及把它切成 chunk 的编码。
 * <pre>
 * chunk = 基本头（fmt 2 位 + chunk stream id）+ 消息头（fmt 0：时间戳 3、长度 3、类型 1、消息流 ID 4，小端）
 *         + [扩展时间戳 4] + 数据（最多 chunkSize 字节）
 * 后续 chunk 用 fmt 3（只有基本头，时间戳超过 0xFFFFFF 时还要带扩展时间戳）
 * </pre>
 */
final class RtmpMessage {

    static final int SET_CHUNK_SIZE = 1;
    static final int ABORT = 2;
    static final int ACK = 3;
    static final int USER_CONTROL = 4;
    static final int WINDOW_ACK_SIZE = 5;
    static final int SET_PEER_BANDWIDTH = 6;
    static final int AUDIO = 8;
    static final int VIDEO = 9;
    static final int DATA_AMF0 = 18;
    static final int COMMAND_AMF0 = 20;

    final int type;
    final int streamId;
    final long timestamp;
    final ByteBuf payload;

    RtmpMessage(int type, int streamId, long timestamp, ByteBuf payload) {
        this.type = type;
        this.streamId = streamId;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    /**
     * 切成 chunk 写进一个新的 ByteBuf。每条消息的第一个 chunk 都用 fmt 0（绝对时间戳），简单可靠
     */
    static ByteBuf encode(ByteBufAllocator alloc, int csid, int type, int streamId, long timestamp, ByteBuf payload, int chunkSize) {
        int len = payload.readableBytes();
        boolean ext = timestamp >= 0xFFFFFF;
        int chunks = Math.max(1, (len + chunkSize - 1) / chunkSize);
        ByteBuf out = alloc.buffer(len + 18 + chunks * (3 + (ext ? 4 : 0)));
        basicHeader(out, 0, csid);
        out.writeMedium(ext ? 0xFFFFFF : (int) timestamp);
        out.writeMedium(len);
        out.writeByte(type);
        out.writeIntLE(streamId);
        if (ext) {
            out.writeInt((int) timestamp);
        }
        int idx = payload.readerIndex();
        int sent = 0;
        while (true) {
            int n = Math.min(chunkSize, len - sent);
            out.writeBytes(payload, idx + sent, n);
            sent += n;
            if (sent >= len) {
                break;
            }
            basicHeader(out, 3, csid);
            if (ext) {
                out.writeInt((int) timestamp);
            }
        }
        return out;
    }

    private static void basicHeader(ByteBuf out, int fmt, int csid) {
        if (csid < 64) {
            out.writeByte((fmt << 6) | csid);
        } else if (csid < 320) {
            out.writeByte(fmt << 6);
            out.writeByte(csid - 64);
        } else {
            out.writeByte((fmt << 6) | 1);
            out.writeShortLE(csid - 64);
        }
    }
}
