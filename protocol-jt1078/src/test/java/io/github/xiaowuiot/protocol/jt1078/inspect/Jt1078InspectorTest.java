package io.github.xiaowuiot.protocol.jt1078.inspect;

import io.github.xiaowuiot.protocol.codec.inspect.Field;
import io.github.xiaowuiot.protocol.codec.inspect.Frame;
import io.github.xiaowuiot.protocol.jt1078.Jt1078Packet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jt1078InspectorTest {

    private static final byte[] KEY_FRAME = {0, 0, 0, 1, 0x67, 0x42, (byte) 0xC0, 0x1E, 0x11,
            0, 0, 0, 1, 0x68, (byte) 0xCE, 0x3C, (byte) 0x80,
            0, 0, 1, 0x65, (byte) 0x88, (byte) 0x84, 0x00, 0x21};

    private final Jt1078Inspector inspector = new Jt1078Inspector();

    private static byte[] packets(int simLength) {
        ByteBuf out = Unpooled.buffer();
        new Jt1078Packet(Jt1078Packet.PT_H264, true, 1, "013800138000", 1, Jt1078Packet.TYPE_I, Jt1078Packet.ATOMIC, 1000, 0, 40, KEY_FRAME)
                .encode(out, simLength);
        new Jt1078Packet(Jt1078Packet.PT_G711A, true, 2, "013800138000", 1, Jt1078Packet.TYPE_AUDIO, Jt1078Packet.ATOMIC, 1020, 0, 0, new byte[160])
                .encode(out, simLength);
        return ByteBufUtil.getBytes(out);
    }

    @Test
    void sixByteSim() {
        byte[] data = packets(6);
        assertTrue(inspector.detect(data) >= 90);
        List<Frame> frames = inspector.inspect(data);
        assertEquals(2, frames.size());
        Frame video = frames.get(0);
        assertTrue(video.getProblems().isEmpty(), video.getProblems().toString());
        assertTrue(video.getMessageName().contains("H.264"));
        Field body = video.getFields().get(1);
        assertEquals(3, body.getChildren().size());
        assertTrue(body.getChildren().get(0).getValue().contains("SPS"));
        assertTrue(body.getChildren().get(2).getValue().contains("IDR"));
        assertTrue(frames.get(1).getMessageName().contains("G.711A"));
    }

    @Test
    void tenByteSim() {
        List<Frame> frames = inspector.inspect(packets(10));
        assertEquals(2, frames.size());
        assertTrue(frames.get(0).getFields().get(0).getValue().contains("10 字节"));
        assertTrue(frames.get(1).getProblems().isEmpty(), frames.get(1).getProblems().toString());
    }
}
