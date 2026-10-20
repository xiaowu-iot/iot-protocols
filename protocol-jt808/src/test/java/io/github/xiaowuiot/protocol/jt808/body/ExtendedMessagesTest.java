package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.codec.util.HexUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.Jt808Version;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.readMessages;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路、电话、信息点播、区域查询、存储多媒体、CAN、压缩、RSA 这些消息的往返测试。
 */
class ExtendedMessagesTest {

    @Test
    void serverTime() {
        LocalDateTime utc = LocalDateTime.of(2026, 10, 20, 2, 30, 15);
        LinkBodies.ServerTime t = roundTrip(Jt808Version.V2019, MessageId.SERVER_TIME_RESPONSE,
                new LinkBodies.ServerTime(utc));
        assertEquals(utc, t.getUtc());
    }

    @Test
    void resendRequestCountIsByteIn2013AndWordIn2019() {
        LinkBodies.ResendRequest req = new LinkBodies.ResendRequest(100, Arrays.asList(2, 5));
        Jt808Message m13 = roundTripMessage(Jt808Version.V2013, MessageId.PLATFORM_RESEND_REQUEST, req);
        // 流水号 2 + 总数 1 + 2 个包序号 4
        assertEquals(7, m13.getHeader().getBodyLength());
        Jt808Message m19 = roundTripMessage(Jt808Version.V2019, MessageId.TERMINAL_RESEND_REQUEST, req);
        assertEquals(8, m19.getHeader().getBodyLength());
        LinkBodies.ResendRequest back = m19.getBody();
        assertEquals(100, back.getOriginalSerialNo());
        assertEquals(Arrays.asList(2, 5), back.getPacketNos());
    }

    @Test
    void callBackAndPhoneBook() {
        PhoneBodies.CallBack cb = roundTrip(Jt808Version.V2013, MessageId.CALL_BACK,
                new PhoneBodies.CallBack(PhoneBodies.CallBack.LISTEN, "13900001111"));
        assertEquals(PhoneBodies.CallBack.LISTEN, cb.getFlag());
        assertEquals("13900001111", cb.getPhone());

        PhoneBodies.PhoneBook book = roundTrip(Jt808Version.V2013, MessageId.PHONE_BOOK,
                new PhoneBodies.PhoneBook(PhoneBodies.PhoneBook.UPDATE, Arrays.asList(
                        new PhoneBodies.Contact(PhoneBodies.Contact.IN_OUT, "075512345678", "调度中心"),
                        new PhoneBodies.Contact(PhoneBodies.Contact.OUT, "13900001111", "车队长"))));
        assertEquals(2, book.getContacts().size());
        assertEquals("调度中心", book.getContacts().get(0).getName());
        assertEquals("13900001111", book.getContacts().get(1).getPhone());

        PhoneBodies.PhoneBook clear = roundTrip(Jt808Version.V2013, MessageId.PHONE_BOOK,
                new PhoneBodies.PhoneBook(PhoneBodies.PhoneBook.DELETE_ALL, Collections.emptyList()));
        assertTrue(clear.getContacts().isEmpty());
    }

    @Test
    void infoService() {
        InfoService.MenuSetting menu = roundTrip(Jt808Version.V2013, MessageId.INFO_MENU_SETTING,
                new InfoService.MenuSetting(1, Arrays.asList(new InfoService.MenuItem(1, "天气"),
                        new InfoService.MenuItem(2, "路况"))));
        assertEquals("路况", menu.getItems().get(1).getName());

        InfoService.Subscribe sub = roundTrip(Jt808Version.V2013, MessageId.INFO_SUBSCRIBE,
                new InfoService.Subscribe(2, true));
        assertTrue(sub.isSubscribe());

        InfoService.Content c = roundTrip(Jt808Version.V2013, MessageId.INFO_SERVICE,
                new InfoService.Content(2, "前方 2 公里拥堵"));
        assertEquals("前方 2 公里拥堵", c.getText());
    }

    @Test
    void areaQuery() {
        AreaQuery.Request req = roundTrip(Jt808Version.V2019, MessageId.AREA_QUERY,
                new AreaQuery.Request(AreaQuery.CIRCLE, Arrays.asList(1L, 2L)));
        assertEquals(Arrays.asList(1L, 2L), req.getIds());

        AreaQuery.Response resp = roundTrip(Jt808Version.V2019, MessageId.AREA_QUERY_RESPONSE,
                new AreaQuery.Response(AreaQuery.ROUTE, 1, new byte[]{1, 2, 3}));
        assertEquals(AreaQuery.ROUTE, resp.getType());
        // 内容不是合法的路线数据，保留原始字节
        assertInstanceOf(byte[].class, resp.getData());
    }

    @Test
    void storedMultimedia() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 20, 9, 0);
        StoredMultimedia.Query q = roundTrip(Jt808Version.V2013, MessageId.STORED_MEDIA_QUERY,
                new StoredMultimedia.Query(0, 1, 0, start, end));
        assertEquals(start, q.getStart());
        assertEquals(end, q.getEnd());

        Location loc = new Location().setLatitude(22.543096).setLongitude(114.057865).setSpeed(36.5)
                .setTime(LocalDateTime.of(2026, 10, 20, 8, 10, 5));
        StoredMultimedia.QueryResponse resp = roundTrip(Jt808Version.V2013, MessageId.STORED_MEDIA_QUERY_RESPONSE,
                new StoredMultimedia.QueryResponse(7, Arrays.asList(
                        new StoredMultimedia.Item(1001, 0, 1, 0, loc),
                        new StoredMultimedia.Item(1002, 0, 2, 0, loc))));
        assertEquals(7, resp.getReplySerialNo());
        assertEquals(1002, resp.getItems().get(1).getMediaId());
        assertEquals(22.543096, resp.getItems().get(0).getLocation().getLatitude(), 1e-6);

        StoredMultimedia.Upload up = roundTrip(Jt808Version.V2013, MessageId.STORED_MEDIA_UPLOAD,
                new StoredMultimedia.Upload(q, true));
        assertTrue(up.isDelete());

        StoredMultimedia.Record rec = roundTrip(Jt808Version.V2013, MessageId.RECORD_AUDIO,
                new StoredMultimedia.Record(true, 30, false, 0));
        assertEquals(30, rec.getSeconds());

        StoredMultimedia.UploadOne one = roundTrip(Jt808Version.V2013, MessageId.STORED_MEDIA_UPLOAD_ONE,
                new StoredMultimedia.UploadOne(1001, false));
        assertEquals(1001, one.getMediaId());
    }

    @Test
    void canBusData() {
        byte[] data = HexUtils.fromHex("01 02 03 04 05 06 07 08");
        CanBusData can = roundTrip(Jt808Version.V2013, MessageId.CAN_BUS_DATA, new CanBusData("0830150123",
                Collections.singletonList(CanBusData.Item.of(1, true, false, 0x18FEF100, data))));
        CanBusData.Item item = can.getItems().get(0);
        assertEquals("0830150123", can.getReceiveTime());
        assertEquals(1, item.getChannel());
        assertTrue(item.isExtended());
        assertEquals(0x18FEF100, item.getBusId());
        assertArrayEquals(data, item.getData());
    }

    @Test
    void compressedData() {
        byte[] raw = "0123456789".concat("0123456789").concat("0123456789").getBytes(StandardCharsets.US_ASCII);
        CompressedData c = roundTrip(Jt808Version.V2013, MessageId.COMPRESSED_DATA, new CompressedData(raw));
        assertArrayEquals(raw, c.getData());
    }

    @Test
    void rsaKey() {
        byte[] n = new byte[128];
        n[0] = (byte) 0xC3;
        n[127] = 0x01;
        RsaPublicKey key = roundTrip(Jt808Version.V2013, MessageId.PLATFORM_RSA_KEY, new RsaPublicKey(65537, n));
        assertEquals(65537, key.getE());
        assertArrayEquals(n, key.getN());
    }

    @Test
    void waybill() {
        byte[] content = "运单 A001".getBytes(StandardCharsets.UTF_8);
        SimpleBodies.Waybill w = roundTrip(Jt808Version.V2013, MessageId.WAYBILL_UPLOAD, new SimpleBodies.Waybill(content));
        assertArrayEquals(content, w.getContent());
    }

    private static <T> T roundTrip(Jt808Version version, int msgId, Jt808Body body) {
        return roundTripMessage(version, msgId, body).getBody();
    }

    private static Jt808Message roundTripMessage(Jt808Version version, int msgId, Jt808Body body) {
        String phone = version == Jt808Version.V2019 ? "13800138000" : "013800138000";
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(new Jt808Message(Jt808Header.of(version, phone, msgId, 1), body)));
        return readMessages(ch).get(0);
    }
}
