package io.github.xiaowuiot.protocol.jt1078.media.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RTMP 收方向：先读握手，再把 chunk 拼回完整的消息。客户端和服务端（测试用）都能用。
 * <p>
 * 握手用"简单握手"：C0（版本 3）+ C1（1536 字节），对方回 S0 + S1 + S2，我方再回 C2（S1 原样）。
 * ZLMediaKit、SRS、nginx-rtmp 都接受简单握手。
 */
final class RtmpDecoder extends ByteToMessageDecoder {

    static final int HANDSHAKE_SIZE = 1536;
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    interface Handshake {
        /**
         * 收到对方的 0 + 1 包（服务端角色收到 C0C1，客户端角色收到 S0S1），返回要回给对方的内容，null 表示不回
         */
        ByteBuf onPeer01(ChannelHandlerContext ctx, ByteBuf part1);

        /**
         * 握手完成
         */
        void done(ChannelHandlerContext ctx);
    }

    private static final class ChunkStream {
        long timestamp;
        long delta;
        int length;
        int type;
        int streamId;
        boolean extended;
        ByteBuf partial;
    }

    private final Handshake handshake;
    private int state;
    private int chunkSize = 128;
    private final Map<Integer, ChunkStream> streams = new HashMap<>();

    RtmpDecoder(Handshake handshake) {
        this.handshake = handshake;
    }

    void setChunkSize(int size) {
        if (size < 1 || size > MAX_MESSAGE) {
            throw new CorruptedFrameException("chunk size 不合法：" + size);
        }
        chunkSize = size;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (state == 0) {
            if (in.readableBytes() < 1 + HANDSHAKE_SIZE) {
                return;
            }
            int version = in.readUnsignedByte();
            if (version != 3) {
                throw new CorruptedFrameException("RTMP 版本不对：" + version);
            }
            ByteBuf reply = handshake.onPeer01(ctx, in.readRetainedSlice(HANDSHAKE_SIZE));
            if (reply != null) {
                ctx.writeAndFlush(reply);
            }
            state = 1;
        }
        if (state == 1) {
            if (in.readableBytes() < HANDSHAKE_SIZE) {
                return;
            }
            in.skipBytes(HANDSHAKE_SIZE);
            state = 2;
            handshake.done(ctx);
        }
        while (in.isReadable()) {
            RtmpMessage m = readChunk(in);
            if (m == RETRY) {
                return;
            }
            if (m != null) {
                if (m.type == RtmpMessage.SET_CHUNK_SIZE) {
                    setChunkSize(m.payload.getInt(m.payload.readerIndex()) & 0x7FFFFFFF);
                }
                out.add(m);
            }
        }
    }

    private static final RtmpMessage RETRY = new RtmpMessage(0, 0, 0, null);

    /**
     * 读一个 chunk。数据不够返回 RETRY（读指针复位）；一条消息没拼完返回 null
     */
    private RtmpMessage readChunk(ByteBuf in) {
        int start = in.readerIndex();
        int b0 = in.readUnsignedByte();
        int fmt = b0 >> 6;
        int csid = b0 & 0x3F;
        if (csid == 0) {
            if (!in.isReadable()) {
                in.readerIndex(start);
                return RETRY;
            }
            csid = 64 + in.readUnsignedByte();
        } else if (csid == 1) {
            if (in.readableBytes() < 2) {
                in.readerIndex(start);
                return RETRY;
            }
            csid = 64 + in.readUnsignedShortLE();
        }
        int headerLen = fmt == 0 ? 11 : fmt == 1 ? 7 : fmt == 2 ? 3 : 0;
        if (in.readableBytes() < headerLen) {
            in.readerIndex(start);
            return RETRY;
        }
        ChunkStream cs = streams.computeIfAbsent(csid, k -> new ChunkStream());
        long ts = 0;
        if (fmt <= 2) {
            ts = in.readUnsignedMedium();
        }
        if (fmt <= 1) {
            cs.length = in.readUnsignedMedium();
            cs.type = in.readUnsignedByte();
            if (cs.length > MAX_MESSAGE) {
                throw new CorruptedFrameException("RTMP 消息过长：" + cs.length);
            }
        }
        if (fmt == 0) {
            cs.streamId = in.readIntLE();
        }
        boolean ext = fmt <= 2 ? ts == 0xFFFFFF : cs.extended;
        if (ext) {
            if (in.readableBytes() < 4) {
                in.readerIndex(start);
                return RETRY;
            }
            long e = in.readUnsignedInt();
            if (fmt <= 2) {
                ts = e;
            }
        }
        boolean first = cs.partial == null;
        int have = first ? 0 : cs.partial.readableBytes();
        int n = Math.min(chunkSize, cs.length - have);
        if (in.readableBytes() < n) {
            in.readerIndex(start);
            return RETRY;
        }
        if (fmt <= 2) {
            cs.extended = ext;
        }
        if (first) {
            if (fmt == 0) {
                cs.timestamp = ts;
                cs.delta = 0;
            } else if (fmt <= 2) {
                cs.delta = ts;
                cs.timestamp += ts;
            } else {
                cs.timestamp += cs.delta;
            }
            cs.partial = in.alloc().buffer(cs.length);
        }
        cs.partial.writeBytes(in, n);
        if (cs.partial.readableBytes() < cs.length) {
            return null;
        }
        ByteBuf payload = cs.partial;
        cs.partial = null;
        return new RtmpMessage(cs.type, cs.streamId, cs.timestamp, payload);
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        for (ChunkStream cs : streams.values()) {
            if (cs.partial != null) {
                cs.partial.release();
                cs.partial = null;
            }
        }
    }
}
