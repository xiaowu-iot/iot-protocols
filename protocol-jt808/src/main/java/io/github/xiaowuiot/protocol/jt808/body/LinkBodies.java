package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.codec.util.BcdUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 链路层的几条消息：查询服务器时间、补传分包请求。
 */
public final class LinkBodies {

    private LinkBodies() {
    }

    /**
     * 查询服务器时间应答 0x8004（2019 版）：UTC 时间 BCD[6]。
     * <p>
     * 终端发 0x0004（消息体为空）请求，平台回这条。注意是 UTC 时间，不是 808 其他地方用的北京时间，
     * 回成北京时间的话终端的时钟会快 8 小时。
     */
    public static final class ServerTime implements Jt808Body {
        private final LocalDateTime utc;

        public ServerTime(LocalDateTime utc) {
            this.utc = utc;
        }

        public static ServerTime decode(ByteBuf b, Jt808Header h) {
            return new ServerTime(BcdUtils.readBcdTime(b));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            BcdUtils.writeBcdTime(out, utc);
        }

        public LocalDateTime getUtc() {
            return utc;
        }

        @Override
        public String toString() {
            return "ServerTime{utc=" + utc + '}';
        }
    }

    /**
     * 补传分包请求：平台发给终端是 0x8003，终端发给平台是 0x0005（2019 版新增），格式一样。
     * <pre>
     * 原始消息流水号 WORD | 重传包总数 BYTE（2019 版 WORD） | 重传包序号 WORD × 总数
     * </pre>
     * 原始消息流水号是分包消息<b>第一包</b>的流水号。收方在超时时间内没收齐分包时，用它要回缺的那几包。
     */
    public static final class ResendRequest implements Jt808Body {
        private final int originalSerialNo;
        private final List<Integer> packetNos;

        public ResendRequest(int originalSerialNo, List<Integer> packetNos) {
            this.originalSerialNo = originalSerialNo;
            this.packetNos = packetNos;
        }

        public static ResendRequest decode(ByteBuf b, Jt808Header h) {
            int serial = b.readUnsignedShort();
            int n = h.isV2019() ? b.readUnsignedShort() : b.readUnsignedByte();
            List<Integer> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(b.readUnsignedShort());
            }
            return new ResendRequest(serial, list);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeShort(originalSerialNo);
            if (h.isV2019()) {
                out.writeShort(packetNos.size());
            } else {
                out.writeByte(packetNos.size());
            }
            for (Integer no : packetNos) {
                out.writeShort(no);
            }
        }

        public int getOriginalSerialNo() {
            return originalSerialNo;
        }

        public List<Integer> getPacketNos() {
            return Collections.unmodifiableList(packetNos);
        }

        @Override
        public String toString() {
            return "ResendRequest{original=" + originalSerialNo + ", packets=" + packetNos + '}';
        }
    }
}
