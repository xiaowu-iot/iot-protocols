package io.github.xiaowuiot.protocol.jt808.codec;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.Jt808Version;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.DriverInfo;
import io.github.xiaowuiot.protocol.jt808.body.InfoBodies;
import io.github.xiaowuiot.protocol.jt808.body.Location;
import io.github.xiaowuiot.protocol.jt808.body.Multimedia;
import io.github.xiaowuiot.protocol.jt808.body.Register;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.readMessages;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2011 / 2013 / 2019 三个版本的识别，以及版本不同、格式不同的几条消息。
 */
class Jt808VersionTest {

    private static Register register() {
        return new Register().setProvince(44).setCity(300).setMakerId("TEST1").setModel("M1")
                .setTerminalId("T000001").setPlateColor(2).setPlate("粤B12345");
    }

    @Test
    void register2011IsRecognizedByLength() {
        // 2011 版注册：2 + 2 + 5 + 8 + 7 + 1 + 车牌 8 = 33 字节，不到 2013 版固定部分的 37 字节
        String hex = encodeHex(new Jt808Message(Jt808Header.of(Jt808Version.V2011, "013800138000", MessageId.REGISTER, 0),
                register()));
        EmbeddedChannel ch = channel();
        writeHex(ch, hex);
        Jt808Message msg = readMessages(ch).get(0);

        assertEquals(33, msg.getHeader().getBodyLength());
        assertEquals(Jt808Version.V2011, msg.getHeader().getVersion());
        Register r = msg.getBody();
        assertEquals("M1", r.getModel());
        assertEquals("T000001", r.getTerminalId());
        assertEquals("粤B12345", r.getPlate());
        // 连接被标记成 2011 版
        assertEquals(Jt808Version.V2011, Jt808Codecs.getVersion(ch));
    }

    @Test
    void register2013() {
        String hex = encodeHex(new Jt808Message(Jt808Header.of(Jt808Version.V2013, "013800138000", MessageId.REGISTER, 0),
                register()));
        EmbeddedChannel ch = channel();
        writeHex(ch, hex);
        Jt808Message msg = readMessages(ch).get(0);

        assertEquals(45, msg.getHeader().getBodyLength());
        assertEquals(Jt808Version.V2013, msg.getHeader().getVersion());
        assertEquals("粤B12345", msg.<Register>getBody().getPlate());
        assertNull(Jt808Codecs.getVersion(ch));
    }

    @Test
    void register2019FromHeader() {
        EmbeddedChannel ch = channel();
        writeHex(ch, Jt808CodecTest.REGISTER_2019);
        Jt808Message msg = readMessages(ch).get(0);
        assertEquals(Jt808Version.V2019, msg.getHeader().getVersion());
        assertTrue(msg.getHeader().isV2019());
    }

    @Test
    void messagesAfter2011RegisterUse2011Format() {
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(new Jt808Message(Jt808Header.of(Jt808Version.V2011, "013800138000", MessageId.REGISTER, 0),
                register())));
        readMessages(ch);

        // 2011 版驾驶员信息：姓名 | 身份证 20 | 从业资格证 40 | 发证机构
        DriverInfo info = new DriverInfo(DriverInfo.CARD_IN, null, DriverInfo.READ_OK, "张三",
                "QC4401001234567890123456", "深圳市交通运输局", null, "440301199001011234");
        writeHex(ch, encodeHex(new Jt808Message(
                Jt808Header.of(Jt808Version.V2011, "013800138000", MessageId.DRIVER_INFO, 1), info)));
        Jt808Message msg = readMessages(ch).get(0);
        assertEquals(Jt808Version.V2011, msg.getHeader().getVersion());
        assertEquals(1 + 4 + 20 + 40 + 1 + 16, msg.getHeader().getBodyLength());
        DriverInfo d = msg.getBody();
        assertEquals("张三", d.getName());
        assertEquals("QC4401001234567890123456", d.getCertNo());
        assertEquals("440301199001011234", d.getIdCard());
        assertEquals("深圳市交通运输局", d.getAuthority());
    }

    @Test
    void multimediaData2011HasNoLocation() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 0x01, 0x02, (byte) 0xFF, (byte) 0xD9};
        EmbeddedChannel ch = channel();
        Jt808Codecs.setVersion(ch, Jt808Version.V2011);
        writeHex(ch, encodeHex(new Jt808Message(Jt808Header.of(Jt808Version.V2011, "013800138000",
                MessageId.MULTIMEDIA_DATA, 1), new Multimedia.Data(9, 0, 0, 0, 1, null, jpeg))));
        Jt808Message msg = readMessages(ch).get(0);
        assertEquals(8 + jpeg.length, msg.getHeader().getBodyLength());
        Multimedia.Data data = msg.getBody();
        assertNull(data.getLocation());
        assertArrayEquals(jpeg, data.getData());
    }

    @Test
    void vehicleControl2013And2019HaveOppositeDoorValues() {
        InfoBodies.VehicleControl lock = new InfoBodies.VehicleControl(true);

        String v13 = encodeHex(new Jt808Message(Jt808Header.of(Jt808Version.V2013, "013800138000",
                MessageId.VEHICLE_CONTROL, 1), lock));
        // 消息体只有 1 个字节：01 加锁
        assertTrue(v13.replace(" ", "").contains("850000010138001380000001" + "01"));

        Jt808Message m19 = roundTrip(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000",
                MessageId.VEHICLE_CONTROL, 1), lock));
        // 控制类型数量 0001 | 类型 0001 车门 | 参数 00 锁闭
        assertEquals(5, m19.getHeader().getBodyLength());
        assertTrue(m19.<InfoBodies.VehicleControl>getBody().isLock());

        Jt808Message unlock19 = roundTrip(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000",
                MessageId.VEHICLE_CONTROL, 2), new InfoBodies.VehicleControl(false)));
        assertFalse(unlock19.<InfoBodies.VehicleControl>getBody().isLock());
    }

    @Test
    void carriageTemperatureIsSignMagnitude() {
        Location l = new Location().setLatitude(22.5).setLongitude(114.0).setTime(LocalDateTime.of(2026, 10, 20, 8, 0))
                .putExtra(Location.EXTRA_CARRIAGE_TEMPERATURE, -18L);
        String hex = encodeHex(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000", MessageId.LOCATION, 1), l));
        // 附加信息 06 02 80 12：最高位是符号，0x12 = 18
        assertTrue(hex.replace(" ", "").contains("06028012"));
        Location back = roundTrip(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000",
                MessageId.LOCATION, 1), l)).getBody();
        assertEquals(-18, back.getCarriageTemperature());
    }

    private static Jt808Message roundTrip(Jt808Message msg) {
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(msg));
        return readMessages(ch).get(0);
    }
}
