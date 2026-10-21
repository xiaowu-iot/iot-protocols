package io.github.xiaowuiot.protocol.jt1078.media;

import io.github.xiaowuiot.protocol.jt1078.FrameAssembler;
import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaStreamTest {

    private static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x42, (byte) 0xC0, 0x1E, 1, 2};
    private static final byte[] PPS = {0, 0, 0, 1, 0x68, (byte) 0xCE, 0x3C, (byte) 0x80};
    private static final byte[] IDR = {0, 0, 0, 1, 0x65, (byte) 0x88, 1, 2, 3};
    private static final byte[] SLICE = {0, 0, 0, 1, 0x41, (byte) 0x9A, 1, 2};

    /** 记下收到的每块数据 */
    private static final class Recorder implements MediaStream.Subscriber {
        final List<byte[]> chunks = new ArrayList<>();

        @Override
        public boolean write(ByteBuf data) {
            byte[] b = new byte[data.readableBytes()];
            data.readBytes(b);
            data.release();
            chunks.add(b);
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public String id() {
            return "rec";
        }

        /** 视频帧 tag 的 [DTS, CTS] */
        List<int[]> videoTimes() {
            List<int[]> out = new ArrayList<>();
            for (byte[] c : chunks) {
                if (c.length > 16 && c[0] == 9 && c[12] == 1) {
                    int ts = ((c[4] & 0xFF) << 16) | ((c[5] & 0xFF) << 8) | (c[6] & 0xFF);
                    int cts = ((c[13] & 0xFF) << 16) | ((c[14] & 0xFF) << 8) | (c[15] & 0xFF);
                    out.add(new int[]{ts, cts});
                }
            }
            return out;
        }
    }

    private static int seq;

    private static void frame(MediaStream s, FrameAssembler a, int pt, int type, long ts, byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] body = new byte[len];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, body, o, p.length);
            o += p.length;
        }
        FrameAssembler.Frame f = a.add(new Jt1078Packet(pt, false, seq++, "013800138000", 1, type,
                Jt1078Packet.ATOMIC, ts, 0, 0, body));
        s.onFrame(f);
    }

    @Test
    void bFramesGetCompositionTime() {
        MediaStream s = new MediaStream("k");
        Recorder r = new Recorder();
        s.addSubscriber(r);
        FrameAssembler a = new FrameAssembler();
        long base = 100_000;
        // 解码顺序 I0 P120 B40 B80 P240 B160 B200（时间戳是显示时间）
        frame(s, a, Jt1078Packet.PT_H264, Jt1078Packet.TYPE_I, base, SPS, PPS, IDR);
        long[][] rest = {{Jt1078Packet.TYPE_P, 120}, {Jt1078Packet.TYPE_B, 40}, {Jt1078Packet.TYPE_B, 80},
                {Jt1078Packet.TYPE_P, 240}, {Jt1078Packet.TYPE_B, 160}, {Jt1078Packet.TYPE_B, 200}};
        for (long[] f : rest) {
            frame(s, a, Jt1078Packet.PT_H264, (int) f[0], base + f[1], SLICE);
        }
        List<int[]> t = r.videoTimes();
        assertEquals(7, t.size());
        for (int i = 1; i < t.size(); i++) {
            assertTrue(t.get(i)[0] > t.get(i - 1)[0], "DTS 要严格递增");
        }
        // P240 解码在两个 B 帧之前，显示时间 = DTS + CTS 仍然是 240
        int[] p240 = t.get(4);
        assertTrue(p240[1] > 0);
        assertEquals(240, p240[0] + p240[1]);
        // 第二组的 B 帧显示时间在 P240 之前
        assertTrue(t.get(5)[0] + t.get(5)[1] < 240);
        assertTrue(t.get(6)[0] + t.get(6)[1] < 240);
    }

    @Test
    void noBFramesMeansZeroCts() {
        MediaStream s = new MediaStream("k");
        Recorder r = new Recorder();
        s.addSubscriber(r);
        FrameAssembler a = new FrameAssembler();
        frame(s, a, Jt1078Packet.PT_H264, Jt1078Packet.TYPE_I, 5000, SPS, PPS, IDR);
        for (int i = 1; i < 10; i++) {
            frame(s, a, Jt1078Packet.PT_H264, Jt1078Packet.TYPE_P, 5000 + i * 40L, SLICE);
        }
        List<int[]> t = r.videoTimes();
        for (int i = 0; i < t.size(); i++) {
            assertEquals(i * 40, t.get(i)[0]);
            assertEquals(0, t.get(i)[1]);
        }
    }

    @Test
    void g711aDecodedToPcmForAudioSubscribers() {
        MediaStream s = new MediaStream("k");
        Recorder r = new Recorder();
        s.addAudioSubscriber(r);
        short[] pcm = new short[160];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(i / 10.0) * 8000);
        }
        byte[] alaw = AudioCodec.pcmToAlaw(pcm);
        // 带海思头：00 01 长度/2 00
        byte[] hisi = {0, 1, (byte) (alaw.length / 2), 0};
        frame(s, new FrameAssembler(), Jt1078Packet.PT_G711A, Jt1078Packet.TYPE_AUDIO, 0, hisi, alaw);
        assertEquals(1, r.chunks.size());
        byte[] c = r.chunks.get(0);
        assertEquals(MediaStream.AUDIO_PCM, c[0]);
        int len = ((c[1] & 0xFF) << 16) | ((c[2] & 0xFF) << 8) | (c[3] & 0xFF);
        assertEquals(320, len);
        for (int i = 0; i < pcm.length; i++) {
            short v = (short) ((c[4 + 2 * i] & 0xFF) | (c[5 + 2 * i] << 8));
            assertTrue(Math.abs(v - pcm[i]) <= Math.max(16, Math.abs(pcm[i]) / 16), "G.711 量化误差 " + i);
        }
    }

    @Test
    void imaAdpcmDecodes() {
        // 状态：上一个采样 1000、步长索引 0；数据 0x77：两个采样都往上走
        byte[] out = AudioCodec.imaAdpcm(new byte[]{(byte) 0xE8, 0x03, 0, 0, 0x77});
        assertEquals(4, out.length);
        short a = (short) ((out[0] & 0xFF) | (out[1] << 8));
        short b = (short) ((out[2] & 0xFF) | (out[3] << 8));
        assertTrue(a > 1000 && b > a);
    }
}
