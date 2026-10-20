package io.github.xiaowuiot.protocol.jt808.sensor;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.Jt808Version;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.PassThrough;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.readMessages;
import static io.github.xiaowuiot.protocol.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SensorsTest {

    @Test
    void parsersAreUsedForPassThrough() {
        Sensors.register();
        byte[] temp = Sensors.encodeTemperature(Arrays.asList(new Sensors.Probe(1, -18.5, 62), new Sensors.Probe(2, -17.9, 60)));
        PassThrough p = roundTrip(Sensors.TYPE_TEMPERATURE, temp);
        List<Sensors.Probe> probes = p.getParsed();
        assertEquals(-18.5, probes.get(0).celsius, 1e-9);
        assertEquals(60, probes.get(1).humidity);

        byte[] tires = Sensors.encodeTires(Arrays.asList(new Sensors.Tire(1, 820, 41, 0), new Sensors.Tire(2, 610, 39, 1)));
        List<Sensors.Tire> t = roundTrip(Sensors.TYPE_TIRE, tires).getParsed();
        assertEquals("低压", t.get(1).getStatusName());

        Sensors.Load load = roundTrip(Sensors.TYPE_LOAD, Sensors.encodeLoad(new Sensors.Load(23_500, 2))).getParsed();
        assertEquals(23_500, load.kg);
        assertEquals("满载", load.getStatusName());
    }

    private static PassThrough roundTrip(int type, byte[] content) {
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(new Jt808Message(Jt808Header.of(Jt808Version.V2013, "013800138000", MessageId.PASS_THROUGH_UP, 1),
                new PassThrough(type, content))));
        return readMessages(ch).get(0).getBody();
    }
}
