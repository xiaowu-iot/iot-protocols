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
    private long maxPts;
    private int reorderDelay;
    private int lastAudioTs;
    private static final int MAX_REORDER_MS = 1000;
    public static final int AUDIO_PCM = 1;
    public static final int AUDIO_AAC = 2;
    private final List<Subscriber> audioSubs = new CopyOnWriteArrayList<>();
    private volatile String audioPayload = "";
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

        /**
         * 旁听：不算观看人数（比如 RTMP 转推），平台里没人看了照样按时关流
         */
        default boolean passive() {
            return false;
        }

        /**
         * 要不要 FLV 音频 tag（RTMP 转推要；浏览器的声音走单独的 .audio，不要）
         */
        default boolean flvAudio() {
            return false;
        }
    }

    private static final class Sub {
        final Subscriber s;
        boolean waitingKey;
        /** 序列头没写进去：下一个关键帧之前要先补发序列头 */
        boolean needHeader;
        /** AAC 的音频序列头（AudioSpecificConfig）已经发过 */
        byte[] aacConfigSent;

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

    /**
     * 观看人数，不含旁听的（RTMP 转推）
     */
    public int subscriberCount() {
        int n = 0;
        for (Sub s : subs) {
            if (!s.s.passive()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 收齐的一帧
     */
    public synchronized void onFrame(FrameAssembler.Frame f) {
        if (closed) {
            return;
        }
        if (!f.isVideo()) {
            onAudio(f);
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
        // 时间戳跳变（终端重启、时钟调整）：按上一帧 + 40ms 续上，播放器看到的时间线始终单调。
        // 有 B 帧时显示时间本来就会往回走一点（B 帧显示在它前面解码的 P 帧之前），1 秒以内不算跳变
        if (rel < maxPts - MAX_REORDER_MS || rel - maxPts > 10_000) {
            baseTimestamp = f.timestamp - maxPts - 40;
            rel = maxPts + 40;
            reorderDelay = 0;
        }
        // 1078 包里的时间戳是显示时间（PTS），FLV tag 的时间戳是解码时间（DTS），差值写在 CTS 里。
        // 没有 B 帧时两者相同；出现 B 帧（显示时间比已解码的帧早）后，按观察到的最大回退量估算重排延迟，
        // DTS = PTS - 重排延迟，并保证 DTS 严格递增
        if (rel < maxPts) {
            reorderDelay = (int) Math.min(MAX_REORDER_MS, Math.max(reorderDelay, maxPts - rel));
        }
        maxPts = Math.max(maxPts, rel);
        int ts = (int) Math.max(frames.get() > 1 ? lastTagTimestamp + 1 : 0, rel - reorderDelay);
        int cts = (int) Math.max(0, rel - ts);
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
        ByteBuf tag = FlvMuxer.videoTag(alloc, codec, key, s.nals, ts, cts);
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

    /**
     * 音频单独分发（{@code /live/{流}.audio}）：每块是 1 字节类型 + 3 字节长度 + 数据。
     * 类型 1 是 16 位小端 PCM（8000 Hz 单声道，G.711 / ADPCM 在这里解码），类型 2 是 AAC（ADTS，浏览器解码）。
     * 音频没有参考帧，写不进去的块直接丢，不用等关键帧。
     */
    private void onAudio(FrameAssembler.Frame f) {
        audioPayload = Jt1078Packet.payloadName(f.payloadType);
        flvAudio(f);
        if (audioSubs.isEmpty()) {
            return;
        }
        int type;
        byte[] data;
        if (AudioCodec.decodable(f.payloadType)) {
            type = AUDIO_PCM;
            data = AudioCodec.toPcm(f.payloadType, f.data);
        } else if (f.payloadType == Jt1078Packet.PT_AAC) {
            type = AUDIO_AAC;
            data = AudioCodec.stripHisiHeader(f.data);
        } else {
            return;
        }
        if (data == null || data.length == 0 || data.length > 0xFFFFFF) {
            return;
        }
        ByteBuf chunk = alloc.buffer(4 + data.length);
        chunk.writeByte(type);
        chunk.writeMedium(data.length);
        chunk.writeBytes(data);
        for (Subscriber sub : audioSubs) {
            sub.write(chunk.retainedDuplicate());
        }
        chunk.release();
    }

    /**
     * 给要 FLV 音频的观看者（RTMP 转推）发音频 tag：G.711 原样（FLV 编码 7 / 8），ADPCM 先解码再转成 G.711A，
     * AAC 去掉 ADTS 头，先发 AudioSpecificConfig 再发裸帧。时间戳和视频用同一个起点
     */
    private void flvAudio(FrameAssembler.Frame f) {
        boolean any = false;
        for (Sub s : subs) {
            if (s.s.flvAudio() && !s.waitingKey) {
                any = true;
                break;
            }
        }
        if (!any || baseTimestamp < 0) {
            return;
        }
        long rel = f.timestamp - baseTimestamp;
        if (rel < 0 || Math.abs(rel - lastTagTimestamp) > 10_000) {
            // 音频时间戳和视频对不上（有的终端音视频时钟不同源）：贴着视频的时间走
            rel = lastTagTimestamp;
        }
        int ts = (int) Math.max(lastAudioTs, rel);
        lastAudioTs = ts;
        byte[] raw = AudioCodec.stripHisiHeader(f.data);
        if (f.payloadType == Jt1078Packet.PT_AAC) {
            for (byte[][] fr : AudioCodec.adtsFrames(raw)) {
                ByteBuf tag = FlvMuxer.audioTag(alloc, FlvMuxer.AUDIO_AAC, 1, fr[1], ts);
                for (Sub s : subs) {
                    if (!s.s.flvAudio() || s.waitingKey) {
                        continue;
                    }
                    if (!Arrays.equals(s.aacConfigSent, fr[0])) {
                        if (!s.s.write(FlvMuxer.audioTag(alloc, FlvMuxer.AUDIO_AAC, 0, fr[0], ts))) {
                            continue;
                        }
                        s.aacConfigSent = fr[0];
                    }
                    s.s.write(tag.retainedDuplicate());
                }
                tag.release();
            }
            return;
        }
        int flags;
        byte[] data;
        if (f.payloadType == Jt1078Packet.PT_G711A) {
            flags = FlvMuxer.AUDIO_G711A;
            data = raw;
        } else if (f.payloadType == Jt1078Packet.PT_G711U) {
            flags = FlvMuxer.AUDIO_G711U;
            data = raw;
        } else if (f.payloadType == Jt1078Packet.PT_ADPCMA) {
            flags = FlvMuxer.AUDIO_G711A;
            data = AudioCodec.pcmToAlaw(AudioCodec.toPcm(f.payloadType, f.data));
        } else {
            return;
        }
        if (data.length == 0) {
            return;
        }
        ByteBuf tag = FlvMuxer.audioTag(alloc, flags, -1, data, ts);
        for (Sub s : subs) {
            if (s.s.flvAudio() && !s.waitingKey) {
                s.s.write(tag.retainedDuplicate());
            }
        }
        tag.release();
    }

    public synchronized void addAudioSubscriber(Subscriber s) {
        if (closed) {
            s.close();
            return;
        }
        audioSubs.add(s);
    }

    public synchronized void removeAudioSubscriber(Subscriber s) {
        audioSubs.remove(s);
    }

    /** 收到的音频格式，没收到过是空字符串 */
    public String audioPayload() {
        return audioPayload;
    }

    private void clearGop() {
        gop.forEach(ByteBuf::release);
        gop.clear();
        gopBytes = 0;
    }

    public synchronized void close() {
        closed = true;
        closeListeners.forEach(r -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                // 回调出错不能影响关流
            }
        });
        closeListeners.clear();
        for (Sub sub : subs) {
            sub.s.close();
        }
        subs.clear();
        audioSubs.forEach(Subscriber::close);
        audioSubs.clear();
        clearGop();
        if (sequenceHeader != null) {
            sequenceHeader.release();
            sequenceHeader = null;
        }
    }

    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();

    /**
     * 流关闭时回调（RTMP 转推还没连上、没加入观看者列表时，也要知道流关了）。已经关了的流立即回调
     */
    public void onClose(Runnable r) {
        synchronized (this) {
            if (!closed) {
                closeListeners.add(r);
                return;
            }
        }
        r.run();
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
