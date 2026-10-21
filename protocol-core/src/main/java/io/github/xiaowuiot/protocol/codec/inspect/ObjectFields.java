package io.github.xiaowuiot.protocol.codec.inspect;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 把解码后的消息体对象转成字段树：按字段声明顺序取 getter。没有逐字节标注的消息靠它显示内容（offset 为 -1）。
 */
public final class ObjectFields {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_ITEMS = 200;

    private ObjectFields() {
    }

    public static Field of(String name, Object obj) {
        Field f = new Field(name, -1, 0, "", null);
        fill(f, obj, 0);
        return f;
    }

    private static void fill(Field parent, Object obj, int depth) {
        if (obj == null) {
            parent.value("（空）");
            return;
        }
        if (simple(obj)) {
            parent.value(text(obj));
            return;
        }
        if (obj instanceof byte[]) {
            byte[] b = (byte[]) obj;
            parent.value(b.length + " 字节：" + hex(b, 64));
            return;
        }
        if (depth >= MAX_DEPTH) {
            parent.value(obj.toString());
            return;
        }
        if (obj instanceof Collection) {
            int i = 0;
            Collection<?> c = (Collection<?>) obj;
            parent.value(c.size() + " 项");
            for (Object o : c) {
                if (i >= MAX_ITEMS) {
                    parent.add(new Field("……", -1, 0, "", "还有 " + (c.size() - i) + " 项"));
                    break;
                }
                Field child = parent.add(new Field("[" + (++i) + "]", -1, 0, "", null));
                fill(child, o, depth + 1);
            }
            return;
        }
        if (obj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) obj;
            parent.value(m.size() + " 项");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Field child = parent.add(new Field(String.valueOf(e.getKey()), -1, 0, "", null));
                fill(child, e.getValue(), depth + 1);
            }
            return;
        }
        for (Getter g : getters(obj.getClass())) {
            Object v;
            try {
                v = g.method != null ? g.method.invoke(obj) : g.field.get(obj);
            } catch (Exception e) {
                continue;
            }
            Field child = parent.add(new Field(g.name, -1, 0, "", null));
            fill(child, v, depth + 1);
        }
    }

    private static boolean simple(Object o) {
        return o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum
                || o instanceof Temporal;
    }

    private static String text(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o ? "是" : "否";
        }
        // 只有时间要把 2026-10-21T08:30 里的 T 换成空格，字符串里的 T（终端型号、车牌）要原样保留
        return o instanceof Temporal ? o.toString().replace('T', ' ') : String.valueOf(o);
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < max; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return b.length > max ? sb + "…" : sb.toString();
    }

    private static final class Getter {
        final String name;
        final Method method;
        final java.lang.reflect.Field field;

        Getter(String name, Method method, java.lang.reflect.Field field) {
            this.name = name;
            this.method = method;
            this.field = field;
        }
    }

    /** 按字段声明顺序（父类在前）找 getter，没有 getter 的 public 字段直接读，字段名当显示名 */
    private static List<Getter> getters(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(0, c);
        }
        List<Getter> out = new ArrayList<>();
        for (Class<?> c : chain) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String n = f.getName();
                String cap = Character.toUpperCase(n.charAt(0)) + n.substring(1);
                Method m = find(type, "get" + cap);
                if (m == null) {
                    m = find(type, "is" + cap);
                }
                if (m != null) {
                    out.add(new Getter(n, m, null));
                } else if (Modifier.isPublic(f.getModifiers()) && Modifier.isPublic(c.getModifiers())) {
                    out.add(new Getter(n, null, f));
                }
            }
        }
        return out;
    }

    private static Method find(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            return Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 0 ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
