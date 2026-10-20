package io.github.xiaowuiot.protocol.jt1078.media;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 H.264 / H.265 的帧封装成 FLV tag，只换封装，不转码。
 * <pre>
 * FLV 头 9 字节 + PreviousTagSize0
 * 视频 tag：类型 9 | 数据长度 3 | 时间戳 3 + 扩展 1 | 流 ID 3 | 帧类型(4) 编码(4) | 包类型 | CTS 3 | 数据 | PreviousTagSize 4
 *   包类型 0：序列头（AVCDecoderConfigurationRecord，放 SPS / PPS）
 *   包类型 1：帧数据（每个 NAL 前面 4 字节长度）
 * </pre>
 * H.265 用国内通行的扩展编码 ID 12（flv.js、mpegts.js 的增强版和 ZLMediaKit 都认），序列头是 HEVCDecoderConfigurationRecord。
 */
public final class FlvMuxer {

    public static final int CODEC_AVC = 7;
    public static final int CODEC_HEVC = 12;

    private FlvMuxer() {
    }

    public static ByteBuf header(ByteBufAllocator alloc, boolean audio) {
        ByteBuf b = alloc.buffer(13);
        b.writeBytes(new byte[]{'F', 'L', 'V', 1});
        b.writeByte(audio ? 0x05 : 0x01);
        b.writeInt(9);
        b.writeInt(0);
        return b;
    }

    /**
     * AVC 序列头
     */
    public static ByteBuf avcSequenceHeader(ByteBufAllocator alloc, byte[] sps, byte[] pps, int timestamp) {
        int len = 5 + 6 + 2 + sps.length + 1 + 2 + pps.length;
        ByteBuf b = alloc.buffer(11 + len + 4);
        tagHeader(b, 9, len, timestamp);
        b.writeByte(0x10 | CODEC_AVC);
        b.writeByte(0);
        b.writeMedium(0);
        b.writeByte(1);
        b.writeByte(sps[1]);
        b.writeByte(sps[2]);
        b.writeByte(sps[3]);
        b.writeByte(0xFF);
        b.writeByte(0xE1);
        b.writeShort(sps.length);
        b.writeBytes(sps);
        b.writeByte(1);
        b.writeShort(pps.length);
        b.writeBytes(pps);
        b.writeInt(11 + len);
        return b;
    }

    /**
     * HEVC 序列头（HEVCDecoderConfigurationRecord 的精简写法：profile 等字段从 SPS 里取最常用的几个，
     * 解码器实际依赖的是后面带的 VPS / SPS / PPS 数组）
     */
    public static ByteBuf hevcSequenceHeader(ByteBufAllocator alloc, byte[] vps, byte[] sps, byte[] pps, int timestamp) {
        byte[][] arrays = {vps, sps, pps};
        int[] types = {H264.HEVC_VPS, H264.HEVC_SPS, H264.HEVC_PPS};
        int len = 5 + 23;
        for (byte[] a : arrays) {
            len += 3 + 2 + a.length;
        }
        ByteBuf b = alloc.buffer(11 + len + 4);
        tagHeader(b, 9, len, timestamp);
        b.writeByte(0x10 | CODEC_HEVC);
        b.writeByte(0);
        b.writeMedium(0);
        // profile_tier_level 在 SPS 的第 3 个字节开始（2 字节 NAL 头 + 1 字节 sps_video_parameter_set_id 等）
        b.writeByte(1);
        b.writeByte(sps.length > 3 ? sps[3] : 1);
        for (int i = 0; i < 4; i++) {
            b.writeByte(sps.length > 4 + i ? sps[4 + i] : 0);
        }
        for (int i = 0; i < 6; i++) {
            b.writeByte(sps.length > 8 + i ? sps[8 + i] : 0);
        }
        b.writeByte(sps.length > 14 ? sps[14] : 93);
        b.writeShort(0xF000);
        b.writeByte(0xFC);
        b.writeByte(0xFD);
        b.writeByte(0xF8);
        b.writeByte(0xF8);
        b.writeShort(0);
        b.writeByte(0x0F);
        b.writeByte(3);
        for (int i = 0; i < 3; i++) {
            b.writeByte(0x80 | types[i]);
            b.writeShort(1);
            b.writeShort(arrays[i].length);
            b.writeBytes(arrays[i]);
        }
        b.writeInt(11 + len);
        return b;
    }

    /**
     * 一帧视频
     *
     * @param nals 不含参数集的 NAL
     */
    public static ByteBuf videoTag(ByteBufAllocator alloc, int codec, boolean key, List<byte[]> nals, int timestamp) {
        int len = 5;
        for (byte[] n : nals) {
            len += 4 + n.length;
        }
        ByteBuf b = alloc.buffer(11 + len + 4);
        tagHeader(b, 9, len, timestamp);
        b.writeByte((key ? 0x10 : 0x20) | codec);
        b.writeByte(1);
        b.writeMedium(0);
        for (byte[] n : nals) {
            b.writeInt(n.length);
            b.writeBytes(n);
        }
        b.writeInt(11 + len);
        return b;
    }

    private static void tagHeader(ByteBuf b, int type, int dataSize, int timestamp) {
        b.writeByte(type);
        b.writeMedium(dataSize);
        b.writeMedium(timestamp & 0xFFFFFF);
        b.writeByte((timestamp >> 24) & 0xFF);
        b.writeMedium(0);
    }

    /**
     * 一帧里的 NAL 按类型分拣：参数集单独拿出来，AUD 丢掉（FLV 里不需要）
     */
    public static final class Sorted {
        public byte[] vps;
        public byte[] sps;
        public byte[] pps;
        public final List<byte[]> nals = new ArrayList<>();
        public boolean idr;
    }

    public static Sorted sort(List<byte[]> nals, boolean hevc) {
        Sorted s = new Sorted();
        for (byte[] n : nals) {
            if (n.length == 0) {
                continue;
            }
            if (hevc) {
                int t = H264.hevcType(n);
                if (t == H264.HEVC_VPS) {
                    s.vps = n;
                } else if (t == H264.HEVC_SPS) {
                    s.sps = n;
                } else if (t == H264.HEVC_PPS) {
                    s.pps = n;
                } else if (t != H264.HEVC_AUD) {
                    if (t >= 16 && t <= 21) {
                        s.idr = true;
                    }
                    s.nals.add(n);
                }
            } else {
                int t = H264.type(n);
                if (t == H264.NAL_SPS) {
                    s.sps = n;
                } else if (t == H264.NAL_PPS) {
                    s.pps = n;
                } else if (t != H264.NAL_AUD) {
                    if (t == H264.NAL_IDR) {
                        s.idr = true;
                    }
                    s.nals.add(n);
                }
            }
        }
        return s;
    }
}
