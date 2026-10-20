package io.github.xiaowuiot.protocol.jt1078;

import io.github.xiaowuiot.protocol.jt1078.media.FlvMuxer;
import io.github.xiaowuiot.protocol.jt1078.media.H264;
import io.github.xiaowuiot.protocol.jt1078.media.MediaStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jt1078Test {

    /** 一个最小的"关键帧"：SPS + PPS + IDR 片（内容不需要能解码，只测拆分和封装） */
    private static final byte[] KEY_FRAME = {0, 0, 0, 1, 0x67, 0x42, (byte) 0xC0, 0x1E, 0x11,
            0, 0, 0, 1, 0x68, (byte) 0xCE, 0x3C, (byte) 0x80,
            0, 0, 1, 0x65, (byte) 0x88, (byte) 0x84, 0x00, 0x21};
    private static final byte[] P_FRAME = {0, 0, 0, 1, 0x41, (byte) 0x9A, 0x02, 0x03};

    private static List<Jt1078Packet> packets(String sim, int channel, byte[] frame, int type, long ts, int seq0, int chunk) {
        List<Jt1078Packet> list = new ArrayList<>();
        int n = (frame.length + chunk - 1) / chunk;
        for (int i = 0; i < n; i++) {
            byte[] body = Arrays.copyOfRange(frame, i * chunk, Math.min(frame.length, (i + 1) * chunk));
            int sub = n == 1 ? Jt1078Packet.ATOMIC : i == 0 ? Jt1078Packet.FIRST : i == n - 1 ? Jt1078Packet.LAST : Jt1078Packet.MIDDLE;
            list.add(new Jt1078Packet(Jt1078Packet.PT_H264, i == n - 1, seq0 + i, sim, channel, type, sub, ts, 0, 40, body));
        }
        return list;
    }

    @Test
    void packetRoundTripAndSimLengthDetection() {
        for (int simLength : new int[]{6, 10}) {
            ByteBuf all = Unpooled.buffer();
            for (Jt1078Packet p : packets("013800138000", 2, KEY_FRAME, Jt1078Packet.TYPE_I, 1000, 0, 7)) {
                p.encode(all, simLength);
            }
            // 再跟一个包：自动识别 SIM 长度要看到下一个帧头
            packets("013800138000", 2, P_FRAME, Jt1078Packet.TYPE_P, 1040, 4, 100).get(0).encode(all, simLength);
            EmbeddedChannel ch = new EmbeddedChannel(new Jt1078PacketDecoder(0));
            // 一个字节一个字节地喂，验证粘包拆包
            while (all.isReadable()) {
                ch.writeInbound(all.readRetainedSlice(1));
            }
            all.release();
            FrameAssembler a = new FrameAssembler();
            FrameAssembler.Frame frame = null;
            Jt1078Packet p;
            int count = 0;
            while ((p = ch.readInbound()) != null) {
                assertEquals("13800138000_2", p.streamKey());
                count++;
                FrameAssembler.Frame f = a.add(p);
                if (f != null && frame == null) {
                    frame = f;
                }
            }
            assertEquals(5, count, "SIM " + simLength + " 字节");
            assertNotNull(frame);
            assertTrue(frame.isKey());
            assertEquals(1000, frame.timestamp);
            assertArrayEquals(KEY_FRAME, frame.data);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void lostPacketDropsWholeFrame() {
        List<Jt1078Packet> ps = packets("13800138000", 1, KEY_FRAME, Jt1078Packet.TYPE_I, 0, 100, 5);
        FrameAssembler a = new FrameAssembler();
        for (int i = 0; i < ps.size(); i++) {
            if (i == 2) {
                continue;
            }
            assertNull(a.add(ps.get(i)));
        }
        assertEquals(1, a.dropped());
        // 下一帧完整的能正常收
        FrameAssembler.Frame f = null;
        for (Jt1078Packet p : packets("13800138000", 1, P_FRAME, Jt1078Packet.TYPE_P, 40, 200, 3)) {
            f = a.add(p);
        }
        assertNotNull(f);
        assertArrayEquals(P_FRAME, f.data);
    }

    @Test
    void splitNalUnits() {
        List<byte[]> nals = H264.split(KEY_FRAME);
        assertEquals(3, nals.size());
        assertEquals(H264.NAL_SPS, H264.type(nals.get(0)));
        assertEquals(H264.NAL_PPS, H264.type(nals.get(1)));
        assertEquals(H264.NAL_IDR, H264.type(nals.get(2)));
        FlvMuxer.Sorted s = FlvMuxer.sort(nals, false);
        assertTrue(s.idr);
        assertEquals(1, s.nals.size());
    }

    /**
     * 新观看者进来先收到 FLV 头、序列头和缓存的 GOP；之后的帧实时收到
     */
    @Test
    void streamSendsGopCacheToNewSubscriber() {
        MediaStream stream = new MediaStream("13800138000_1");
        stream.onFrame(frame(KEY_FRAME, Jt1078Packet.TYPE_I, 1000));
        stream.onFrame(frame(P_FRAME, Jt1078Packet.TYPE_P, 1040));
        List<ByteBuf> got = new ArrayList<>();
        stream.addSubscriber(new MediaStream.Subscriber() {
            @Override
            public boolean write(ByteBuf data) {
                got.add(data);
                return true;
            }

            @Override
            public void close() {
            }

            @Override
            public String id() {
                return "test";
            }
        });
        // FLV 头 + 序列头 + 关键帧 + P 帧
        assertEquals(4, got.size());
        assertEquals('F', got.get(0).getByte(0));
        assertEquals(9, got.get(1).getByte(0));
        assertEquals(0x17, got.get(1).getUnsignedByte(11));
        assertEquals(0, got.get(1).getByte(12));
        assertEquals(0x17, got.get(2).getUnsignedByte(11));
        assertEquals(0x27, got.get(3).getUnsignedByte(11));
        // 时间戳从第一帧开始算
        assertEquals(40, got.get(3).getUnsignedMedium(4));
        stream.onFrame(frame(P_FRAME, Jt1078Packet.TYPE_P, 1080));
        assertEquals(5, got.size());
        assertEquals(80, got.get(4).getUnsignedMedium(4));
        got.forEach(ByteBuf::release);
        stream.close();
    }

    private static FrameAssembler.Frame frame(byte[] data, int type, long ts) {
        FrameAssembler a = new FrameAssembler();
        return a.add(new Jt1078Packet(Jt1078Packet.PT_H264, true, 0, "13800138000", 1, type, Jt1078Packet.ATOMIC, ts, 0, 40, data));
    }
}
