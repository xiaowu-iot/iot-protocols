package io.github.xiaowuiot.protocol.jt1078;

import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.github.xiaowuiot.protocol.codec.util.ByteBufUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.body.HasReplySerial;
import io.github.xiaowuiot.protocol.jt808.body.Jt808Body;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1078 在 808 上扩展的信令（走 808 的连接，不是码流）。
 * <pre>
 * 平台 ─0x9101 实时音视频传输请求─▶ 终端      告诉终端媒体服务器的地址、要哪个通道
 * 平台 ─0x9102 实时音视频传输控制─▶ 终端      关闭、暂停、切换主子码流
 * 平台 ─0x9003 查询音视频属性────▶ 终端 ─0x1003─▶
 * 平台 ─0x9205 查询资源列表──────▶ 终端 ─0x1205─▶   终端里存了哪些录像
 * 平台 ─0x9201 远程录像回放请求──▶ 终端      终端连媒体服务器推录像
 * 平台 ─0x9202 远程录像回放控制──▶ 终端      暂停、快进、拖动、结束
 * </pre>
 * 终端收到 0x9101 / 0x9201 后另起一条 TCP 连接到媒体服务器推流，码流格式见 {@link Jt1078Packet}。
 */
public final class Jt1078Bodies {

    public static final int AV_ATTRIBUTES_QUERY = 0x9003;
    public static final int AV_ATTRIBUTES = 0x1003;
    public static final int REALTIME_REQUEST = 0x9101;
    public static final int REALTIME_CONTROL = 0x9102;
    public static final int PLAYBACK_REQUEST = 0x9201;
    public static final int PLAYBACK_CONTROL = 0x9202;
    public static final int RESOURCE_QUERY = 0x9205;
    public static final int RESOURCE_LIST = 0x1205;

    /** 数据类型：0 音视频 1 视频 2 双向对讲 3 监听 4 中心广播 5 透传 */
    public static final int DATA_AV = 0;
    public static final int DATA_VIDEO = 1;
    public static final int DATA_TALK = 2;
    public static final int DATA_LISTEN = 3;

    /** 码流类型：0 主码流 1 子码流 */
    public static final int STREAM_MAIN = 0;
    public static final int STREAM_SUB = 1;

    private Jt1078Bodies() {
    }

    /**
     * 0x9101 实时音视频传输请求
     * <pre>服务器 IP 长度 BYTE | 服务器 IP | TCP 端口 WORD | UDP 端口 WORD | 逻辑通道号 BYTE | 数据类型 BYTE | 码流类型 BYTE</pre>
     */
    public static final class RealtimeRequest implements Jt808Body {
        public final String host;
        public final int tcpPort;
        public final int udpPort;
        public final int channel;
        public final int dataType;
        public final int streamType;

        public RealtimeRequest(String host, int tcpPort, int udpPort, int channel, int dataType, int streamType) {
            this.host = host;
            this.tcpPort = tcpPort;
            this.udpPort = udpPort;
            this.channel = channel;
            this.dataType = dataType;
            this.streamType = streamType;
        }

        public static RealtimeRequest decode(ByteBuf b, Jt808Header h) {
            String host = ByteBufUtils.readByteLengthString(b, StandardCharsets.US_ASCII);
            return new RealtimeRequest(host, b.readUnsignedShort(), b.readUnsignedShort(), b.readUnsignedByte(),
                    b.readUnsignedByte(), b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            ByteBufUtils.writeByteLengthString(out, host, StandardCharsets.US_ASCII);
            out.writeShort(tcpPort).writeShort(udpPort).writeByte(channel).writeByte(dataType).writeByte(streamType);
        }

        @Override
        public String toString() {
            return "RealtimeRequest{" + host + ":" + tcpPort + ", 通道 " + channel + ", 数据类型 " + dataType
                    + ", " + (streamType == STREAM_SUB ? "子码流" : "主码流") + '}';
        }
    }

    /**
     * 0x9102 实时音视频传输控制
     * <pre>逻辑通道号 BYTE | 控制指令 BYTE | 关闭音视频类型 BYTE | 切换码流类型 BYTE</pre>
     * 控制指令：0 关闭传输 1 切换码流 2 暂停 3 恢复 4 关闭对讲
     */
    public static final class RealtimeControl implements Jt808Body {
        public static final int CLOSE = 0;
        public static final int SWITCH_STREAM = 1;
        public static final int PAUSE = 2;
        public static final int RESUME = 3;
        public static final int CLOSE_TALK = 4;

        public final int channel;
        public final int command;
        public final int closeType;
        public final int streamType;

        public RealtimeControl(int channel, int command, int closeType, int streamType) {
            this.channel = channel;
            this.command = command;
            this.closeType = closeType;
            this.streamType = streamType;
        }

        public static RealtimeControl decode(ByteBuf b, Jt808Header h) {
            return new RealtimeControl(b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(channel).writeByte(command).writeByte(closeType).writeByte(streamType);
        }

        @Override
        public String toString() {
            return "RealtimeControl{通道 " + channel + ", 指令 " + command + '}';
        }
    }

    /**
     * 0x1003 终端上传音视频属性
     */
    public static final class AvAttributes implements Jt808Body {
        public final int audioCodec;
        public final int audioChannels;
        public final int sampleRate;
        public final int sampleBits;
        public final int audioFrameLength;
        public final boolean audioOutput;
        public final int videoCodec;
        public final int maxAudioChannels;
        public final int maxVideoChannels;

        public AvAttributes(int audioCodec, int audioChannels, int sampleRate, int sampleBits, int audioFrameLength,
                            boolean audioOutput, int videoCodec, int maxAudioChannels, int maxVideoChannels) {
            this.audioCodec = audioCodec;
            this.audioChannels = audioChannels;
            this.sampleRate = sampleRate;
            this.sampleBits = sampleBits;
            this.audioFrameLength = audioFrameLength;
            this.audioOutput = audioOutput;
            this.videoCodec = videoCodec;
            this.maxAudioChannels = maxAudioChannels;
            this.maxVideoChannels = maxVideoChannels;
        }

        public static AvAttributes decode(ByteBuf b, Jt808Header h) {
            return new AvAttributes(b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(),
                    b.readUnsignedShort(), b.readUnsignedByte() == 1, b.readUnsignedByte(), b.readUnsignedByte(),
                    b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(audioCodec).writeByte(audioChannels).writeByte(sampleRate).writeByte(sampleBits)
                    .writeShort(audioFrameLength).writeByte(audioOutput ? 1 : 0).writeByte(videoCodec)
                    .writeByte(maxAudioChannels).writeByte(maxVideoChannels);
        }

        @Override
        public String toString() {
            return "AvAttributes{音频 " + Jt1078Packet.payloadName(audioCodec) + ", 视频 " + Jt1078Packet.payloadName(videoCodec)
                    + ", 最大音频通道 " + maxAudioChannels + ", 最大视频通道 " + maxVideoChannels + '}';
        }
    }

    /**
     * 0x9205 查询资源列表
     * <pre>
     * 逻辑通道号 BYTE（0 所有通道）| 开始时间 BCD[6] | 结束时间 BCD[6] | 报警标志 64BITS |
     * 音视频资源类型 BYTE | 码流类型 BYTE | 存储器类型 BYTE
     * </pre>
     * 开始、结束时间全 0 表示不限
     */
    public static final class ResourceQuery implements Jt808Body {
        public final int channel;
        public final LocalDateTime start;
        public final LocalDateTime end;
        public final long alarm;
        public final int avType;
        public final int streamType;
        public final int storageType;

        public ResourceQuery(int channel, LocalDateTime start, LocalDateTime end, long alarm, int avType, int streamType,
                             int storageType) {
            this.channel = channel;
            this.start = start;
            this.end = end;
            this.alarm = alarm;
            this.avType = avType;
            this.streamType = streamType;
            this.storageType = storageType;
        }

        public static ResourceQuery decode(ByteBuf b, Jt808Header h) {
            return new ResourceQuery(b.readUnsignedByte(), readTime(b), readTime(b), b.readLong(), b.readUnsignedByte(),
                    b.readUnsignedByte(), b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(channel);
            writeTime(out, start);
            writeTime(out, end);
            out.writeLong(alarm).writeByte(avType).writeByte(streamType).writeByte(storageType);
        }

        @Override
        public String toString() {
            return "ResourceQuery{通道 " + channel + ", " + start + " ~ " + end + '}';
        }
    }

    /**
     * 资源列表里的一项：一段录像文件
     */
    public static final class Resource {
        public final int channel;
        public final LocalDateTime start;
        public final LocalDateTime end;
        public final long alarm;
        public final int avType;
        public final int streamType;
        public final int storageType;
        public final long size;

        public Resource(int channel, LocalDateTime start, LocalDateTime end, long alarm, int avType, int streamType,
                        int storageType, long size) {
            this.channel = channel;
            this.start = start;
            this.end = end;
            this.alarm = alarm;
            this.avType = avType;
            this.streamType = streamType;
            this.storageType = storageType;
            this.size = size;
        }

        public int getChannel() {
            return channel;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }

        public long getAlarm() {
            return alarm;
        }

        public int getAvType() {
            return avType;
        }

        public int getStreamType() {
            return streamType;
        }

        public int getStorageType() {
            return storageType;
        }

        public long getSize() {
            return size;
        }
    }

    /**
     * 0x1205 终端上传音视频资源列表
     * <pre>应答流水号 WORD | 资源总数 DWORD | 资源 × N（每项 28 字节）</pre>
     */
    public static final class ResourceList implements Jt808Body, HasReplySerial {
        public final int replySerialNo;
        public final List<Resource> items;

        public ResourceList(int replySerialNo, List<Resource> items) {
            this.replySerialNo = replySerialNo;
            this.items = items;
        }

        public static ResourceList decode(ByteBuf b, Jt808Header h) {
            int serial = b.readUnsignedShort();
            long n = b.readUnsignedInt();
            List<Resource> list = new ArrayList<>();
            for (long i = 0; i < n && b.readableBytes() >= 28; i++) {
                list.add(new Resource(b.readUnsignedByte(), readTime(b), readTime(b), b.readLong(), b.readUnsignedByte(),
                        b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedInt()));
            }
            return new ResourceList(serial, list);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeShort(replySerialNo);
            out.writeInt(items.size());
            for (Resource r : items) {
                out.writeByte(r.channel);
                writeTime(out, r.start);
                writeTime(out, r.end);
                out.writeLong(r.alarm).writeByte(r.avType).writeByte(r.streamType).writeByte(r.storageType)
                        .writeInt((int) r.size);
            }
        }

        @Override
        public int getReplySerialNo() {
            return replySerialNo;
        }

        public List<Resource> getItems() {
            return Collections.unmodifiableList(items);
        }

        @Override
        public String toString() {
            return "ResourceList{" + items.size() + " 个资源}";
        }
    }

    /**
     * 0x9201 平台下发远程录像回放请求
     * <pre>
     * 服务器 IP 长度 BYTE | 服务器 IP | TCP 端口 WORD | UDP 端口 WORD | 逻辑通道号 BYTE | 音视频类型 BYTE |
     * 码流类型 BYTE | 存储器类型 BYTE | 回放方式 BYTE | 快进或快退倍数 BYTE | 开始时间 BCD[6] | 结束时间 BCD[6]
     * </pre>
     */
    public static final class PlaybackRequest implements Jt808Body {
        public final String host;
        public final int tcpPort;
        public final int udpPort;
        public final int channel;
        public final int avType;
        public final int streamType;
        public final int storageType;
        public final int mode;
        public final int speed;
        public final LocalDateTime start;
        public final LocalDateTime end;

        public PlaybackRequest(String host, int tcpPort, int udpPort, int channel, int avType, int streamType,
                               int storageType, int mode, int speed, LocalDateTime start, LocalDateTime end) {
            this.host = host;
            this.tcpPort = tcpPort;
            this.udpPort = udpPort;
            this.channel = channel;
            this.avType = avType;
            this.streamType = streamType;
            this.storageType = storageType;
            this.mode = mode;
            this.speed = speed;
            this.start = start;
            this.end = end;
        }

        public static PlaybackRequest decode(ByteBuf b, Jt808Header h) {
            String host = ByteBufUtils.readByteLengthString(b, StandardCharsets.US_ASCII);
            return new PlaybackRequest(host, b.readUnsignedShort(), b.readUnsignedShort(), b.readUnsignedByte(),
                    b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(),
                    b.readUnsignedByte(), readTime(b), readTime(b));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            ByteBufUtils.writeByteLengthString(out, host, StandardCharsets.US_ASCII);
            out.writeShort(tcpPort).writeShort(udpPort).writeByte(channel).writeByte(avType).writeByte(streamType)
                    .writeByte(storageType).writeByte(mode).writeByte(speed);
            writeTime(out, start);
            writeTime(out, end);
        }

        @Override
        public String toString() {
            return "PlaybackRequest{" + host + ":" + tcpPort + ", 通道 " + channel + ", " + start + " ~ " + end + '}';
        }
    }

    /**
     * 0x9202 平台下发远程录像回放控制
     * <pre>逻辑通道号 BYTE | 回放控制 BYTE | 快进或快退倍数 BYTE | 拖动回放位置 BCD[6]</pre>
     * 回放控制：0 开始回放 1 暂停 2 结束 3 快进 4 关键帧快退 5 拖动 6 关键帧播放
     */
    public static final class PlaybackControl implements Jt808Body {
        public static final int START = 0;
        public static final int PAUSE = 1;
        public static final int STOP = 2;
        public static final int FAST_FORWARD = 3;
        public static final int SEEK = 5;

        public final int channel;
        public final int command;
        public final int speed;
        public final LocalDateTime position;

        public PlaybackControl(int channel, int command, int speed, LocalDateTime position) {
            this.channel = channel;
            this.command = command;
            this.speed = speed;
            this.position = position;
        }

        public static PlaybackControl decode(ByteBuf b, Jt808Header h) {
            return new PlaybackControl(b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), readTime(b));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(channel).writeByte(command).writeByte(speed);
            writeTime(out, position);
        }

        @Override
        public String toString() {
            return "PlaybackControl{通道 " + channel + ", 指令 " + command + '}';
        }
    }

    /**
     * BCD 时间，全 0 表示"不限"，读成 null
     */
    static LocalDateTime readTime(ByteBuf b) {
        String s = BcdUtils.readBcd(b, 6);
        if (s.matches("0+")) {
            return null;
        }
        return LocalDateTime.of(2000 + Integer.parseInt(s.substring(0, 2)), Integer.parseInt(s.substring(2, 4)),
                Integer.parseInt(s.substring(4, 6)), Integer.parseInt(s.substring(6, 8)), Integer.parseInt(s.substring(8, 10)),
                Integer.parseInt(s.substring(10, 12)));
    }

    static void writeTime(ByteBuf out, LocalDateTime t) {
        if (t == null) {
            out.writeZero(6);
        } else {
            BcdUtils.writeBcdTime(out, t);
        }
    }
}
