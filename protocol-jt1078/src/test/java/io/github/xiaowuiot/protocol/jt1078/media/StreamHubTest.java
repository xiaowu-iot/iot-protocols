package io.github.xiaowuiot.protocol.jt1078.media;

import io.github.xiaowuiot.protocol.jt1078.FrameAssembler;
import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamHubTest {

    private static final class Sub implements MediaStream.Subscriber {
        boolean closed;

        @Override
        public boolean write(ByteBuf data) {
            data.release();
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public String id() {
            return "test";
        }
    }

    @Test
    void newPublisherKicksOldOneAndOldUnpublishIsIgnored() {
        StreamHub hub = new StreamHub(30, 20);
        AtomicInteger kicked = new AtomicInteger();
        Object a = new Object();
        Object b = new Object();
        Sub viewer = new Sub();
        hub.subscribe("13800138000_1", viewer);
        MediaStream s1 = hub.publish("13800138000_1", a, kicked::incrementAndGet);
        MediaStream s2 = hub.publish("13800138000_1", b, () -> {
        });
        assertSame(s1, s2);
        assertEquals(1, kicked.get());
        // 旧连接断开：已经不是当前推流者，流不受影响
        hub.unpublish("13800138000_1", a);
        assertTrue(hub.get("13800138000_1").isPublishing());
        assertFalse(viewer.closed);
        hub.shutdown();
    }

    @Test
    void closeRemovesStreamAndKicksPublisher() {
        StreamHub hub = new StreamHub(30, 20);
        AtomicInteger kicked = new AtomicInteger();
        Sub viewer = new Sub();
        hub.subscribe("k_pb", viewer);
        MediaStream s = hub.publish("k_pb", new Object(), kicked::incrementAndGet);
        hub.close("k_pb");
        assertNull(hub.get("k_pb"));
        assertTrue(s.isClosed());
        assertTrue(viewer.closed);
        assertEquals(1, kicked.get());
        // 关闭后的流再来帧直接丢弃，新观看者直接断开
        Sub late = new Sub();
        s.addSubscriber(late);
        assertTrue(late.closed);
        hub.shutdown();
    }

    @Test
    void deniesByDefault() {
        StreamHub hub = new StreamHub(30, 20);
        assertFalse(hub.authorizer().allowPublish("k"));
        assertFalse(hub.authorizer().allowPlay("k", "t"));
        hub.shutdown();
    }

    @Test
    void audioBetweenVideoSubpackagesDoesNotBreakTheFrame() {
        FrameAssembler a = new FrameAssembler();
        List<FrameAssembler.Frame> out = new ArrayList<>();
        int seq = 0;
        Object[][] packets = {
                {Jt1078Packet.TYPE_I, Jt1078Packet.FIRST},
                {Jt1078Packet.TYPE_AUDIO, Jt1078Packet.ATOMIC},
                {Jt1078Packet.TYPE_I, Jt1078Packet.MIDDLE},
                {Jt1078Packet.TYPE_PASSTHROUGH, Jt1078Packet.ATOMIC},
                {Jt1078Packet.TYPE_I, Jt1078Packet.LAST},
        };
        for (Object[] p : packets) {
            FrameAssembler.Frame f = a.add(new Jt1078Packet(Jt1078Packet.PT_H264, false, seq++, "013800138000", 1,
                    (Integer) p[0], (Integer) p[1], 1000, 0, 0, new byte[]{1, 2}));
            if (f != null) {
                out.add(f);
            }
        }
        assertEquals(2, out.size());
        assertFalse(out.get(0).isVideo());
        FrameAssembler.Frame video = out.get(1);
        assertNotNull(video);
        assertTrue(video.isKey());
        assertEquals(6, video.data.length);
        assertEquals(0, a.dropped());
    }
}
