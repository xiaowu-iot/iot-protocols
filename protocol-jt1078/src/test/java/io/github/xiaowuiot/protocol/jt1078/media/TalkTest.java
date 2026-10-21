package io.github.xiaowuiot.protocol.jt1078.media;

import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;
import io.github.xiaowuiot.protocol.jt1078.Jt1078PacketDecoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 双向对讲端到端：假终端推音频、收平台下发的音频；WebSocket 客户端扮演浏览器
 */
class TalkTest {

    private static final String KEY = "13800138000_36";
    private StreamHub hub;
    private MediaServer server;
    private EventLoopGroup group;
    private int livePort;
    private int httpPort;

    @BeforeEach
    void start() throws Exception {
        hub = new StreamHub(30, 20);
        hub.setAuthorizer(new StreamHub.Authorizer() {
            @Override
            public boolean allowPublish(String key) {
                return true;
            }

            @Override
            public boolean allowPlay(String key, String token) {
                return "ok".equals(token);
            }
        });
        livePort = freePort();
        httpPort = freePort();
        server = new MediaServer(hub, "127.0.0.1", livePort, 0, httpPort, 0);
        server.start();
        group = new NioEventLoopGroup(1);
    }

    @AfterEach
    void stop() {
        server.stop();
        group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitFor(BooleanSupplier c, String what) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (c.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        fail("等不到：" + what);
    }

    @Test
    void browserVoiceReachesTerminalAndBack() throws Exception {
        // 假终端：连上推流端口，推一帧 G.711A 音频；收平台发回来的包
        List<Jt1078Packet> fromPlatform = new CopyOnWriteArrayList<>();
        Channel terminal = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new Jt1078PacketDecoder(6)).addLast(new SimpleChannelInboundHandler<Jt1078Packet>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, Jt1078Packet p) {
                        fromPlatform.add(p);
                    }
                });
            }
        }).connect("127.0.0.1", livePort).sync().channel();
        // SIM 卡号长度自动识别要看到下一个包的包头，真终端是连续推的，这里先推两个
        sendTerminalAudio(terminal, 0);
        sendTerminalAudio(terminal, 1);
        waitFor(() -> hub.get(KEY) != null && hub.get(KEY).isPublishing(), "终端推流");

        // 令牌不对：拒绝升级，连接被关掉
        Channel bad = connectWs("bad", new CopyOnWriteArrayList<>());
        bad.closeFuture().await(2000);
        assertTrue(!bad.isActive(), "令牌不对要拒绝");

        List<ByteBuf> toBrowser = new CopyOnWriteArrayList<>();
        Channel ws = connectWs("ok", toBrowser);
        assertTrue(ws.isActive());
        assertEquals(1, hub.get(KEY).subscriberCount(), "对讲算一个观看者，流不会因为没人看被关掉");

        // 浏览器说话：两帧 PCM（1280 字节）→ 终端收到两个 G.711A 音频包
        ws.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[1280])));
        waitFor(() -> fromPlatform.size() >= 2, "终端收到平台的声音");
        Jt1078Packet p = fromPlatform.get(0);
        assertEquals(Jt1078Packet.PT_G711A, p.payloadType);
        assertEquals(Jt1078Packet.TYPE_AUDIO, p.dataType);
        assertEquals(36, p.channel);
        assertEquals(4 + 320, p.body.length, "海思头 4 字节 + 320 个采样");
        assertEquals(1, p.body[1]);

        // 终端说话 → 浏览器收到解码后的 PCM 分块
        sendTerminalAudio(terminal, 2);
        sendTerminalAudio(terminal, 3);
        waitFor(() -> !toBrowser.isEmpty(), "浏览器收到终端的声音");
        ByteBuf chunk = toBrowser.get(0);
        assertEquals(MediaStream.AUDIO_PCM, chunk.getUnsignedByte(0));
        assertEquals(320, chunk.getUnsignedMedium(1));

        ws.close().sync();
        waitFor(() -> hub.get(KEY) == null || hub.get(KEY).subscriberCount() == 0, "对讲结束后观看人数归零");
        toBrowser.forEach(ByteBuf::release);
        terminal.close().sync();
    }

    private void sendTerminalAudio(Channel terminal, int seq) {
        byte[] body = new byte[4 + 160];
        body[1] = 1;
        body[2] = 80;
        Jt1078Packet p = new Jt1078Packet(Jt1078Packet.PT_G711A, true, seq, "013800138000", 36, Jt1078Packet.TYPE_AUDIO,
                Jt1078Packet.ATOMIC, System.currentTimeMillis(), 0, 0, body);
        ByteBuf b = terminal.alloc().buffer();
        p.encode(b, 6);
        terminal.writeAndFlush(b);
    }

    private Channel connectWs(String token, List<ByteBuf> received) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + httpPort + "/live/../talk/" + KEY + "?token=" + token);
        WebSocketClientHandshaker hs = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders());
        Channel ch = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel c) {
                c.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(65536), new SimpleChannelInboundHandler<Object>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                        if (!hs.isHandshakeComplete()) {
                            FullHttpResponse r = (FullHttpResponse) msg;
                            if (r.status().code() == 101) {
                                hs.finishHandshake(ctx.channel(), r);
                            } else {
                                ctx.close();
                            }
                        } else if (msg instanceof BinaryWebSocketFrame) {
                            received.add(((WebSocketFrame) msg).content().retain());
                        }
                    }
                });
            }
        }).connect("127.0.0.1", httpPort).sync().channel();
        hs.handshake(ch).sync();
        for (int i = 0; i < 100 && !hs.isHandshakeComplete() && ch.isActive(); i++) {
            Thread.sleep(20);
        }
        return ch;
    }
}
