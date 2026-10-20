package io.github.xiaowuiot.protocol.jt808.inspect;

import io.github.xiaowuiot.protocol.codec.inspect.Field;
import io.github.xiaowuiot.protocol.codec.inspect.Frame;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jt808InspectorTest {

    private final Jt808Inspector inspector = new Jt808Inspector();

    static Field find(List<Field> fields, String name) {
        for (Field f : fields) {
            if (f.getName().startsWith(name)) {
                return f;
            }
            Field c = find(f.getChildren(), name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    @Test
    void buildThenInspectLocation() {
        Map<String, String> v = new HashMap<>();
        v.put("version", "2013");
        v.put("phone", "13800138000");
        v.put("lat", "22.543096");
        v.put("lng", "114.057865");
        v.put("speed", "45.5");
        v.put("time", "2026-10-21 08:30:00");
        byte[] data = inspector.build("0x0200", v);
        assertEquals(0x7E, data[0] & 0xFF);
        assertTrue(inspector.detect(data) >= 90);

        List<Frame> frames = inspector.inspect(data);
        assertEquals(1, frames.size());
        Frame f = frames.get(0);
        assertEquals("0x0200", f.getMessageId());
        assertTrue(f.getProblems().isEmpty(), f.getProblems().toString());
        assertTrue(find(f.getFields(), "纬度").getValue().contains("22.543096"));
        assertTrue(find(f.getFields(), "校验码").getValue().contains("正确"));
    }

    @Test
    void stickyPacketsAndBadChecksum() {
        byte[] a = inspector.build("0x0002", new HashMap<>());
        byte[] b = a.clone();
        b[b.length - 2] ^= 0x01;
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        List<Frame> frames = inspector.inspect(both);
        assertEquals(2, frames.size());
        assertTrue(frames.get(0).getProblems().isEmpty(), frames.get(0).getProblems().toString());
        assertFalse(frames.get(1).getProblems().isEmpty());
    }

    @Test
    void notFooledByGb32960() {
        // GB/T 32960 心跳：## 07 FE VIN(17) 01 0000 BCC。## 异或为 0，所以它的 BCC 正好也满足 808 的算法
        byte[] gb = new byte[25];
        gb[0] = '#';
        gb[1] = '#';
        gb[2] = 0x07;
        gb[3] = (byte) 0xFE;
        byte[] vin = "LDEMO000000000001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(vin, 0, gb, 4, 17);
        gb[21] = 0x01;
        int bcc = 0;
        for (int i = 2; i < 24; i++) {
            bcc ^= gb[i];
        }
        gb[24] = (byte) bcc;
        assertTrue(inspector.detect(gb) < 40);
    }

    @Test
    void catalog() {
        assertTrue(inspector.messages().size() > 50);
        assertFalse(inspector.templates().isEmpty());
    }
}
