package io.github.xiaowuiot.protocol.jt808.codec;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubpackageAssemblerTest {

    private static Jt808Header header(int msgId, int total, int no) {
        Jt808Header h = new Jt808Header();
        h.setMsgId(msgId);
        h.setSubpackage(true);
        h.setTotalPackets(total);
        h.setPacketNo(no);
        return h;
    }

    private static ByteBuf body(int n) {
        return Unpooled.wrappedBuffer(new byte[n]);
    }

    @Test
    void mergesAndTracksBytes() {
        SubpackageAssembler a = new SubpackageAssembler(60_000, 16);
        assertNull(a.add(header(0x0801, 2, 1), body(100), ByteBufAllocator.DEFAULT));
        assertEquals(100, a.pendingBytes());
        ByteBuf merged = a.add(header(0x0801, 2, 2), body(50), ByteBufAllocator.DEFAULT);
        assertNotNull(merged);
        assertEquals(150, merged.readableBytes());
        merged.release();
        assertEquals(0, a.pendingBytes());
        assertEquals(0, a.pendingGroups());
    }

    @Test
    void groupLimitDropsOldest() {
        SubpackageAssembler a = new SubpackageAssembler(60_000, 16, 2, 1 << 20);
        a.add(header(0x0801, 3, 1), body(10), ByteBufAllocator.DEFAULT);
        a.add(header(0x0802, 3, 1), body(10), ByteBufAllocator.DEFAULT);
        a.add(header(0x0803, 3, 1), body(10), ByteBufAllocator.DEFAULT);
        assertEquals(2, a.pendingGroups());
        assertEquals(20, a.pendingBytes());
        a.releaseAll();
    }

    @Test
    void byteLimitDropsGroup() {
        SubpackageAssembler a = new SubpackageAssembler(60_000, 16, 4, 150);
        a.add(header(0x0801, 3, 1), body(100), ByteBufAllocator.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> a.add(header(0x0801, 3, 2), body(100), ByteBufAllocator.DEFAULT));
        assertEquals(0, a.pendingGroups());
        assertEquals(0, a.pendingBytes());
    }
}
