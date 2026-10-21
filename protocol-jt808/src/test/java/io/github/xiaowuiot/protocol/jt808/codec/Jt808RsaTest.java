package io.github.xiaowuiot.protocol.jt808.codec;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.Jt808Version;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.Location;
import io.github.xiaowuiot.protocol.jt808.body.RsaPublicKey;
import io.github.xiaowuiot.protocol.jt808.body.TextMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.LocalDateTime;

import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.channel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jt808RsaTest {

    @Test
    void bothDirectionsEncrypted() {
        Jt808Rsa platform = new Jt808Rsa(Jt808Rsa.newKeyPair(), true);
        KeyPair terminalKeys = Jt808Rsa.newKeyPair();
        Jt808Rsa terminal = new Jt808Rsa(terminalKeys, true);

        EmbeddedChannel p = channel();
        p.attr(Jt808Rsa.KEY).set(platform);
        EmbeddedChannel t = channel();
        t.attr(Jt808Rsa.KEY).set(terminal);
        // 交换公钥：终端拿到平台公钥（0x8A00），平台拿到终端公钥（0x0A00）
        t.attr(Jt808Rsa.TERMINAL_KEY).set(Jt808Rsa.toPublicKey(platform.platformPublicKey()));
        RsaPublicKey terminalPub = terminal.platformPublicKey();
        Jt808Rsa.setTerminalKey(p, terminalPub);

        // 终端 → 平台：位置汇报加密
        Location loc = new Location().setLatitude(22.543096).setLongitude(114.057865).setSpeed(45.5)
                .setTime(LocalDateTime.of(2026, 10, 21, 8, 30, 0));
        t.writeOutbound(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000", MessageId.LOCATION, 1), loc));
        ByteBuf up = t.readOutbound();
        assertTrue(up.readableBytes() > 128, "加密后的消息体至少一个 128 字节的块");
        p.writeInbound(up);
        Jt808Message got = p.readInbound();
        assertNotNull(got);
        assertEquals(1, got.getHeader().getEncryption() & Jt808Rsa.RSA);
        assertEquals(22.543096, got.<Location>getBody().getLatitude(), 1e-6);

        // 平台 → 终端：文本下发加密（长文本跨多个 RSA 块）
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("请注意安全驾驶");
        }
        p.writeOutbound(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000", MessageId.TEXT_MESSAGE, 2),
                new TextMessage(0x0C, text.toString())));
        ByteBuf down = p.readOutbound();
        t.writeInbound(down);
        Jt808Message txt = t.readInbound();
        assertEquals(text.toString(), txt.<TextMessage>getBody().getText());

        // 平台公钥本身不加密（终端还没有平台公钥时也要能解开）
        p.writeOutbound(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000", MessageId.PLATFORM_RSA_KEY, 3),
                platform.platformPublicKey()));
        ByteBuf keyMsg = p.readOutbound();
        EmbeddedChannel plain = channel();
        plain.writeInbound(keyMsg);
        assertNotNull(plain.readInbound());
    }

    @Test
    void encryptedMessageWithoutPlatformKeyIsDropped() {
        Jt808Rsa terminal = new Jt808Rsa(Jt808Rsa.newKeyPair(), true);
        EmbeddedChannel t = channel();
        t.attr(Jt808Rsa.KEY).set(terminal);
        t.attr(Jt808Rsa.TERMINAL_KEY).set(Jt808Rsa.newKeyPair().getPublic());
        t.writeOutbound(new Jt808Message(Jt808Header.of(Jt808Version.V2019, "13800138000", MessageId.LOCATION, 1), new Location().setLatitude(1).setLongitude(1).setTime(LocalDateTime.now())));
        EmbeddedChannel p = channel();
        p.writeInbound((Object) t.readOutbound());
        assertNull(p.readInbound());
    }
}
