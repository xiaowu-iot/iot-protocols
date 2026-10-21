package io.github.xiaowuiot.protocol.jt1078.media;

import io.github.xiaowuiot.protocol.jt1078.FrameAssembler;
import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一路流（一台车的一个通道）：收帧、转成 FLV tag、分发给所有观看者。
 * <p>
 * <b>秒开</b>：缓存从最近一个关键帧开始的整组画面（GOP），新观看者先收到序列头 + GOP，马上就能解出画面，
 * 不用等下一个关键帧（终端的关键帧间隔常见 2~5 秒）。
 * <p>
 * <b>慢客户端</b>：观看者的网络跟不上（Netty 写缓冲超过高水位）时，这个观看者跳过后面的帧，
 * 一直到下一个关键帧再继续，不会拖慢其他人，也不会把服务器内存撑爆。
 * <p>
 * <b>不拷贝</b>：一帧只封装一次，给每个观看者的是同一块内存的引用（retainedDuplicate）。
 */
public class MediaStream {

    /** GOP 缓存上限：关键帧间隔异常大（或者终端一直不发关键帧）时不能无限涨 */
    private static final int MAX_GOP_FRAMES = 300;
    private static final long MAX_GOP_BYTES = 16L * 1024 * 1024;

    private final String key;
    private final ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
    private final List<ByteBuf> gop = new ArrayList<>();
    private long gopBytes;
    private boolean closed;
    private ByteBuf sequenceHeader;
    private int codec = FlvMuxer.CODEC_AVC;
    private byte[] vps;
    private byte[] sps;
    private byte[] pps;
    private long baseTimestamp = -1;
    private int lastTagTimestamp;
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private volatile long lastFrameAt;
    private volatile boolean publishing;
    private final long createdAt = System.currentTimeMillis();
    private volatile String payload = "";

    public MediaStream(String key) {
        this.key = key;
    }

    /**
     * 一个观看者
     */
    public interface Subscriber {
        /**
         * 写一块数据。返回 false 表示写不进去（网络积压），后面的帧会跳到下一个关键帧
         */
        boolean write(ByteBuf data);

        void close();

        String id();
    }

    private static final class Sub {
        final Subscriber s;
        boolean waitingKey;
        /** 序列头没写进去：下一个关键帧之前要先补发序列头 */
        boolean needHeader;

        Sub(Subscriber s) {
            this.s = s;
        }
    }

    private final List<Sub> subs = new CopyOnWriteArrayList<>();

    public synchronized void addSubscriber(Subscriber s) {
        if (closed) {
            // 流刚被关掉（没人看、等推流超时）：观看者直接断开，页面会重新请求
            s.close();
            return;
        }
        Sub sub = new Sub(s);
        subs.add(sub);
        s.write(FlvMuxer.header(alloc, false));
        if (sequenceHeader == null) {
            sub.waitingKey = true;
            sub.needHeader = true;
            return;
        }
        if (!s.write(sequenceHeader.retainedDuplicate())) {
            sub.waitingKey = true;
            sub.needHeader = true;
            return;
        }
        if (gop.isEmpty()) {
            sub.waitingKey = true;
            return;
        }
        // 秒开：整组画面一起发。中途写不进去就停，后面的 P 帧没有参考帧会花屏，等下一个关键帧
        for (ByteBuf tag : gop) {
            if (!s.write(tag.retainedDuplicate())) {
                sub.waitingKey = true;
                break;
            }
        }
    }

    public synchronized void removeSubscriber(Subscriber s) {
        subs.removeIf(x -> x.s == s);
    }

    public int subscriberCount() {
        return subs.size();
    }

    /**
     * 收齐的一帧
     */
    public synchronized void onFrame(FrameAssembler.Frame f) {
        if (closed) {
            return;
        }
        if (!f.isVideo()) {
            // 音频：第一版先不分发（浏览器端解 G.711 留到后面）
            return;
        }
        frames.incrementAndGet();
        bytesIn.addAndGet(f.data.length);
        lastFrameAt = System.currentTimeMillis();
        boolean hevc = f.payloadType == Jt1078Packet.PT_H265;
        payload = Jt1078Packet.payloadName(f.payloadType);
        FlvMuxer.Sorted s = FlvMuxer.sort(H264.split(f.data), hevc);
        if (baseTimestamp < 0) {
            baseTimestamp = f.timestamp;
        }
        long rel = f.timestamp - baseTimestamp;
        // 时间戳跳变（终端重启、时钟调整）：按上一帧 + 40ms 续上，播放器看到的时间线始终单调
        if (rel < lastTagTimestamp || rel - lastTagTimestamp > 10_000) {
            baseTimestamp = f.timestamp - lastTagTimestamp - 40;
            rel = lastTagTimestamp + 40;
        }
        int ts = (int) rel;
        lastTagTimestamp = ts;
        boolean changed = false;
        // H.265 的序列头要 VPS + SPS + PPS，缺 VPS 时先不建，否则播放器按 HEVC 解 AVC 序列头会直接失败
        if (s.sps != null && s.pps != null && (!hevc || s.vps != null)
                && (sps == null || !Arrays.equals(sps, s.sps) || !Arrays.equals(pps, s.pps))) {
            sps = s.sps;
            pps = s.pps;
            vps = s.vps;
            changed = true;
        }
        if (changed) {
            codec = hevc ? FlvMuxer.CODEC_HEVC : FlvMuxer.CODEC_AVC;
            if (sequenceHeader != null) {
                sequenceHeader.release();
            }
            sequenceHeader = hevc ? FlvMuxer.hevcSequenceHeader(alloc, vps, sps, pps, ts)
                    : FlvMuxer.avcSequenceHeader(alloc, sps, pps, ts);
            for (Sub sub : subs) {
                if (!sub.s.write(sequenceHeader.retainedDuplicate())) {
                    sub.needHeader = true;
                    sub.waitingKey = true;
                } else {
                    sub.needHeader = false;
                }
            }
        }
        if (sequenceHeader == null || s.nals.isEmpty()) {
            return;
        }
        boolean key = f.isKey() || s.idr;
        ByteBuf tag = FlvMuxer.videoTag(alloc, codec, key, s.nals, ts);
        if (key) {
            clearGop();
        }
        if (key || !gop.isEmpty()) {
            gop.add(tag.retain());
            gopBytes += tag.readableBytes();
            if (gop.size() > MAX_GOP_FRAMES || gopBytes > MAX_GOP_BYTES) {
                // 不能只删开头：删掉关键帧后新观看者先收到一串 P 帧会花屏。整组清掉，新观看者等下一个关键帧
                clearGop();
            }
        }
        for (Sub sub : subs) {
            if (sub.waitingKey) {
                if (!key) {
                    continue;
                }
                if (sub.needHeader) {
                    if (!sub.s.write(sequenceHeader.retainedDuplicate())) {
                        continue;
                    }
                    sub.needHeader = false;
                }
                sub.waitingKey = false;
            }
            if (!sub.s.write(tag.retainedDuplicate())) {
                sub.waitingKey = true;
            }
        }
        tag.release();
    }

    private void clearGop() {
        gop.forEach(ByteBuf::release);
        gop.clear();
        gopBytes = 0;
    }

    public synchronized void close() {
        closed = true;
        for (Sub sub : subs) {
            sub.s.close();
        }
        subs.clear();
        clearGop();
        if (sequenceHeader != null) {
            sequenceHeader.release();
            sequenceHeader = null;
        }
    }

    public String getKey() {
        return key;
    }

    public long frames() {
        return frames.get();
    }

    public long bytesIn() {
        return bytesIn.get();
    }

    public long lastFrameAt() {
        return lastFrameAt;
    }

    public long createdAt() {
        return createdAt;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public boolean isPublishing() {
        return publishing;
    }

    void setPublishing(boolean publishing) {
        this.publishing = publishing;
    }

    public String payload() {
        return payload;
    }
}
