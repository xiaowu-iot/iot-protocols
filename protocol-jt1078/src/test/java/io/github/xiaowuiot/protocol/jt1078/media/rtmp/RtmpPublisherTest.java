package io.github.xiaowuiot.protocol.jt1078.media.rtmp;

import io.github.xiaowuiot.protocol.jt1078.FrameAssembler;
import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;
import io.github.xiaowuiot.protocol.jt1078.media.AudioCodec;
import io.github.xiaowuiot.protocol.jt1078.media.MediaStream;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 测试里起一个最小的 RTMP 服务端（扮演 ZLMediaKit / SRS）：握手、回 connect / createStream / publish，记下收到的音视频
 */
class RtmpPublisherTest {

    private static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x42, (byte) 0xC0, 0x1E, 1, 2};
    private static final byte[] PPS = {0, 0, 0, 1, 0x68, (byte) 0xCE, 0x3C, (byte) 0x80};
    private static final byte[] IDR = {0, 0, 0, 1, 0x65, (byte) 0x88, 1, 2, 3};
    private static final byte[] SLICE = {0, 0, 0, 1, 0x41, (byte) 0x9A, 1, 2};

    private EventLoopGroup serverGroup;
    private Channel server;
    private int port;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final List<RtmpMessage> media = new CopyOnWriteArrayList<>();
    private final List<Channel> clients = new CopyOnWriteArrayList<>();
    private volatile String publishedName;
    private volatile String connectApp;
    private volatile boolean rejectPublish;
    private int seq;

    @BeforeEach
    void startServer() throws Exception {
        serverGroup = new NioEventLoopGroup(1);
        server = new ServerBootstrap().group(serverGroup).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        clients.add(ch);
                        FakeServer s = new FakeServer();
                        ch.pipeline().addLast(new RtmpDecoder(s)).addLast(s);
                    }
                }).bind("127.0.0.1", 0).sync().channel();
        port = ((InetSocketAddress) server.localAddress()).getPort();
    }

    @AfterEach
    void stopServer() {
        server.close();
        serverGroup.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS);
        media.forEach(m -> m.payload.release());
    }

    private final class FakeServer extends SimpleChannelInboundHandler<RtmpMessage> implements RtmpDecoder.Handshake {
        @Override
        public ByteBuf onPeer01(ChannelHandlerContext ctx, ByteBuf c1) {
            ByteBuf r = ctx.alloc().buffer(1 + 2 * RtmpDecoder.HANDSHAKE_SIZE);
            r.writeByte(3);
            r.writeZero(RtmpDecoder.HANDSHAKE_SIZE);
            r.writeBytes(c1);
            c1.release();
            return r;
        }

        @Override
        public void done(ChannelHandlerContext ctx) {
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RtmpMessage m) {
            if (m.type == RtmpMessage.AUDIO || m.type == RtmpMessage.VIDEO) {
                media.add(new RtmpMessage(m.type, m.streamId, m.timestamp, m.payload.retain()));
                m.payload.release();
                return;
            }
            try {
                if (m.type != RtmpMessage.COMMAND_AMF0) {
                    return;
                }
                List<Object> c = Amf0.readAll(m.payload);
                String name = (String) c.get(0);
                commands.add(name);
                double txn = (Double) c.get(1);
                switch (name) {
                    case "connect":
                        connectApp = String.valueOf(((Map<?, ?>) c.get(2)).get("app"));
                        reply(ctx, 0, "_result", txn, status("status", "NetConnection.Connect.Success"));
                        break;
                    case "createStream":
                        reply(ctx, 0, "_result", txn, null, 1.0);
                        break;
                    case "publish":
                        publishedName = (String) c.get(3);
                        reply(ctx, 1, "onStatus", 0, null, rejectPublish
                                ? status("error", "NetStream.Publish.BadName") : status("status", "NetStream.Publish.Start"));
                        break;
                    default:
                        break;
                }
            } finally {
                m.payload.release();
            }
        }

        private Map<String, Object> status(String level, String code) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("code", code);
            return m;
        }

        private void reply(ChannelHandlerContext ctx, int msid, String name, double txn, Object... args) {
            ByteBuf p = ctx.alloc().buffer();
            Amf0.write(p, name);
            Amf0.write(p, txn);
            for (Object a : args) {
                Amf0.write(p, a);
            }
            ctx.writeAndFlush(RtmpMessage.encode(ctx.alloc(), 3, RtmpMessage.COMMAND_AMF0, msid, 0, p, 128));
            p.release();
        }
    }

    private void frame(MediaStream s, FrameAssembler a, int pt, int type, long ts, byte[]... parts) {
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
        s.onFrame(a.add(new Jt1078Packet(pt, false, seq++, "013800138000", 1, type, Jt1078Packet.ATOMIC, ts, 0, 0, body)));
    }

    private static void waitFor(BooleanSupplier cond, String what) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等不到：" + what);
    }

    @Test
    void publishesVideoAndAudio() throws Exception {
        MediaStream s = new MediaStream("13800138000_1");
        FrameAssembler a = new FrameAssembler();
        // 转推开始前流里已经有一组画面：发布成功后要先收到序列头和这组画面
        frame(s, a, Jt1078Packet.PT_H264, Jt1078Packet.TYPE_I, 1000, SPS, PPS, IDR);
        frame(s, a, Jt1078Packet.PT_H264, Jt1078Packet.TYPE_P, 1040, SLICE);
        RtmpRelay relay = RtmpRelay.template("rtmp://127.0.0.1:" + port + "/live/{key}?sign=abc");
        relay.attach(s);
        relay.attach(s);
        waitFor(() -> media.size() >= 3, "序列头和 GOP");
        assertEquals("live", connectApp);
        assertEquals("13800138000_1?sign=abc", publishedName);
        assertEquals(1, commands.stream().filter("publish"::equals).count(), "重复 attach 不能建第二条转推");
        assertEquals(RtmpPublisher.State.PUBLISHING, relay.get("13800138000_1").getState());
        assertEquals(0, s.subscriberCount(), "转推是旁听，不算观看人数");

        RtmpMessage seqHeader = media.get(0);
        assertEquals(RtmpMessage.VIDEO, seqHeader.type);
        assertEquals(0x17, seqHeader.payload.getUnsignedByte(0));
        assertEquals(0, seqHeader.payload.getUnsignedByte(1), "第一条是 AVC 序列头");
        assertEquals(0x17, media.get(1).payload.getUnsignedByte(0), "然后是关键帧");
        assertEquals(1, media.get(1).streamId);
        assertEquals(40, media.get(2).timestamp);

        // G.711A 原样转发
        short[] pcm = new short[160];
        frame(s, a, Jt1078Packet.PT_G711A, Jt1078Packet.TYPE_AUDIO, 1080, AudioCodec.pcmToAlaw(pcm));
        // AAC：ADTS（7 字节头，LC，8000 Hz，单声道）+ 3 字节数据
        byte[] adts = {(byte) 0xFF, (byte) 0xF1, (byte) 0x6C, (byte) 0x40, 0x01, 0x40, (byte) 0xFC, 1, 2, 3};
        frame(s, a, Jt1078Packet.PT_AAC, Jt1078Packet.TYPE_AUDIO, 1120, adts);
        frame(s, a, Jt1078Packet.PT_H264, Jt1078Packet.TYPE_P, 1160, SLICE);
        waitFor(() -> media.size() >= 6, "音频和后面的视频");
        RtmpMessage g711 = media.get(3);
        assertEquals(RtmpMessage.AUDIO, g711.type);
        assertEquals(0x72, g711.payload.getUnsignedByte(0));
        assertEquals(1 + 160, g711.payload.readableBytes());
        RtmpMessage aacConfig = media.get(4);
        assertEquals(0xAF, aacConfig.payload.getUnsignedByte(0));
        assertEquals(0, aacConfig.payload.getUnsignedByte(1), "AAC 先发 AudioSpecificConfig");
        byte[] asc = new byte[2];
        aacConfig.payload.getBytes(2, asc);
        assertArrayEquals(new byte[]{0x15, (byte) 0x88}, asc, "LC、8000 Hz（索引 11）、单声道");
        RtmpMessage aac = media.get(5);
        assertEquals(1, aac.payload.getUnsignedByte(1));
        assertEquals(2 + 3, aac.payload.readableBytes(), "裸帧去掉了 ADTS 头");

        // 流关闭：转推断开，不再重连
        s.close();
        waitFor(() -> !clients.get(0).isActive(), "转推连接断开");
        assertEquals(RtmpPublisher.State.STOPPED, relay.list().isEmpty() ? RtmpPublisher.State.STOPPED : relay.list().get(0).getState());
        relay.shutdown();
    }

    @Test
    void retriesWhenServerRejects() throws Exception {
        rejectPublish = true;
        MediaStream s = new MediaStream("13800138000_2");
        RtmpRelay relay = RtmpRelay.template("rtmp://127.0.0.1:" + port + "/live/{key}");
        relay.attach(s);
        waitFor(() -> relay.get("13800138000_2").getLastError().contains("BadName"), "拒绝原因");
        assertTrue(relay.get("13800138000_2").getState() != RtmpPublisher.State.PUBLISHING);
        // 对端恢复后自动重连发布
        rejectPublish = false;
        waitFor(() -> relay.get("13800138000_2").getState() == RtmpPublisher.State.PUBLISHING, "重连成功");
        s.close();
        relay.shutdown();
    }
}
