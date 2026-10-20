package io.github.xiaowuiot.protocol.jt1078.media;

import java.util.ArrayList;
import java.util.List;

/**
 * H.264 / H.265 码流（Annex B）的拆分：终端推上来的帧是"起始码 00 00 01 / 00 00 00 01 + NAL"的序列，
 * FLV 里要的是"4 字节长度 + NAL"，参数集（SPS、PPS，H.265 还有 VPS）要单独放进序列头。
 */
public final class H264 {

    public static final int NAL_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_AUD = 9;

    public static final int HEVC_VPS = 32;
    public static final int HEVC_SPS = 33;
    public static final int HEVC_PPS = 34;
    public static final int HEVC_AUD = 35;

    private H264() {
    }

    /**
     * 按起始码拆出 NAL（不含起始码）
     */
    public static List<byte[]> split(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        int n = data.length;
        int start = -1;
        int i = 0;
        while (i + 2 < n) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (i + 3 < n && data[i + 2] == 0 && data[i + 3] == 1))) {
                int codeLength = data[i + 2] == 1 ? 3 : 4;
                if (start >= 0) {
                    add(list, data, start, i);
                }
                start = i + codeLength;
                i = start;
            } else {
                i++;
            }
        }
        if (start >= 0) {
            add(list, data, start, n);
        } else if (n > 0) {
            // 没有起始码：有的终端每包就是一个裸 NAL
            list.add(data);
        }
        return list;
    }

    private static void add(List<byte[]> list, byte[] data, int from, int to) {
        // 去掉 NAL 末尾的 0（下一个 4 字节起始码的第一个 0 会被算进来）
        while (to > from && data[to - 1] == 0) {
            to--;
        }
        if (to > from) {
            byte[] nal = new byte[to - from];
            System.arraycopy(data, from, nal, 0, nal.length);
            list.add(nal);
        }
    }

    public static int type(byte[] nal) {
        return nal[0] & 0x1F;
    }

    public static int hevcType(byte[] nal) {
        return (nal[0] >> 1) & 0x3F;
    }
}
