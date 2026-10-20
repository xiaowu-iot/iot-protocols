package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.xiaowuiot.protocol.codec.util.ByteBufUtils.GBK;

/**
 * 信息点播服务（2011/2013 版，2019 版已删除）：平台下发点播菜单，司机在终端上订阅，平台再按类型推送信息（比如天气、路况）。
 * <pre>
 * 平台 ── 0x8303 信息点播菜单设置 ──► 终端
 * 平台 ◄── 0x0303 信息点播/取消 ──── 终端
 * 平台 ── 0x8304 信息服务 ──────────► 终端
 * </pre>
 */
public final class InfoService {

    private InfoService() {
    }

    /**
     * 0x8303 信息点播菜单设置。
     * <pre>
     * 设置类型 BYTE（0 删除全部，1 更新，2 追加，3 修改）| 信息项总数 BYTE
     * | 信息项：信息类型 BYTE | 信息名称长度 WORD | 信息名称 STRING
     * </pre>
     */
    public static final class MenuSetting implements Jt808Body {
        private final int type;
        private final List<MenuItem> items;

        public MenuSetting(int type, List<MenuItem> items) {
            this.type = type;
            this.items = items;
        }

        public static MenuSetting decode(ByteBuf b, Jt808Header h) {
            int type = b.readUnsignedByte();
            List<MenuItem> list = new ArrayList<>();
            if (b.isReadable()) {
                int n = b.readUnsignedByte();
                for (int i = 0; i < n; i++) {
                    int infoType = b.readUnsignedByte();
                    list.add(new MenuItem(infoType, readWordLengthString(b, GBK)));
                }
            }
            return new MenuSetting(type, list);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(type);
            if (type == 0) {
                return;
            }
            out.writeByte(items.size());
            for (MenuItem item : items) {
                out.writeByte(item.infoType);
                writeWordLengthString(out, item.name, GBK);
            }
        }

        public int getType() {
            return type;
        }

        public List<MenuItem> getItems() {
            return Collections.unmodifiableList(items);
        }

        @Override
        public String toString() {
            return "InfoMenuSetting{type=" + type + ", items=" + items + '}';
        }
    }

    public static final class MenuItem {
        private final int infoType;
        private final String name;

        public MenuItem(int infoType, String name) {
            this.infoType = infoType;
            this.name = name;
        }

        public int getInfoType() {
            return infoType;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return infoType + ":" + name;
        }
    }

    /**
     * 0x0303 信息点播/取消：信息类型 BYTE | 点播/取消标志 BYTE（0 取消，1 点播）
     */
    public static final class Subscribe implements Jt808Body {
        private final int infoType;
        private final boolean subscribe;

        public Subscribe(int infoType, boolean subscribe) {
            this.infoType = infoType;
            this.subscribe = subscribe;
        }

        public static Subscribe decode(ByteBuf b, Jt808Header h) {
            return new Subscribe(b.readUnsignedByte(), b.readUnsignedByte() == 1);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(infoType);
            out.writeByte(subscribe ? 1 : 0);
        }

        public int getInfoType() {
            return infoType;
        }

        public boolean isSubscribe() {
            return subscribe;
        }

        @Override
        public String toString() {
            return "InfoSubscribe{type=" + infoType + ", " + (subscribe ? "点播" : "取消") + '}';
        }
    }

    /**
     * 0x8304 信息服务：信息类型 BYTE | 信息长度 WORD | 信息内容 STRING
     */
    public static final class Content implements Jt808Body {
        private final int infoType;
        private final String text;

        public Content(int infoType, String text) {
            this.infoType = infoType;
            this.text = text;
        }

        public static Content decode(ByteBuf b, Jt808Header h) {
            return new Content(b.readUnsignedByte(), readWordLengthString(b, GBK));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(infoType);
            writeWordLengthString(out, text, GBK);
        }

        public int getInfoType() {
            return infoType;
        }

        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return "InfoContent{type=" + infoType + ", text=" + text + '}';
        }
    }

    static String readWordLengthString(ByteBuf b, Charset cs) {
        int len = b.readUnsignedShort();
        return b.readCharSequence(len, cs).toString();
    }

    static void writeWordLengthString(ByteBuf out, String s, Charset cs) {
        int lengthIndex = out.writerIndex();
        out.writeShort(0);
        int len = out.writeCharSequence(s, cs);
        out.setShort(lengthIndex, len);
    }
}
