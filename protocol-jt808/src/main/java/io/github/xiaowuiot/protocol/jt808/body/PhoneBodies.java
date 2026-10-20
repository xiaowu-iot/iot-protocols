package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.codec.util.ByteBufUtils;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.xiaowuiot.protocol.codec.util.ByteBufUtils.GBK;

/**
 * 电话相关：电话回拨 0x8400、设置电话本 0x8401。终端都回通用应答。
 */
public final class PhoneBodies {

    private PhoneBodies() {
    }

    /**
     * 电话回拨 0x8400：标志 BYTE（0 普通通话，1 监听）| 电话号码 STRING（最长 20 字节）。
     * <p>
     * 监听就是终端拨过去之后关掉自己的喇叭，平台这边能听到车内的声音，常用于出租车、两客一危车辆。
     */
    public static final class CallBack implements Jt808Body {
        public static final int NORMAL = 0;
        public static final int LISTEN = 1;

        private final int flag;
        private final String phone;

        public CallBack(int flag, String phone) {
            this.flag = flag;
            this.phone = phone;
        }

        public static CallBack decode(ByteBuf b, Jt808Header h) {
            return new CallBack(b.readUnsignedByte(), ByteBufUtils.readRemainingString(b, GBK));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(flag);
            out.writeCharSequence(phone, GBK);
        }

        public int getFlag() {
            return flag;
        }

        public String getPhone() {
            return phone;
        }

        @Override
        public String toString() {
            return "CallBack{" + (flag == LISTEN ? "监听" : "通话") + ", phone=" + phone + '}';
        }
    }

    /**
     * 设置电话本 0x8401。
     * <pre>
     * 设置类型 BYTE（0 删除全部，1 更新：先删除全部再追加，2 追加，3 修改：按联系人匹配）| 联系人总数 BYTE
     * | 联系人项：标志 BYTE（1 呼入，2 呼出，3 呼入/呼出）| 号码长度 BYTE | 号码 STRING | 联系人长度 BYTE | 联系人 STRING
     * </pre>
     * 删除全部时没有后面的联系人项。
     */
    public static final class PhoneBook implements Jt808Body {
        public static final int DELETE_ALL = 0;
        public static final int UPDATE = 1;
        public static final int APPEND = 2;
        public static final int MODIFY = 3;

        private final int type;
        private final List<Contact> contacts;

        public PhoneBook(int type, List<Contact> contacts) {
            this.type = type;
            this.contacts = contacts;
        }

        public static PhoneBook decode(ByteBuf b, Jt808Header h) {
            int type = b.readUnsignedByte();
            List<Contact> list = new ArrayList<>();
            if (type != DELETE_ALL && b.isReadable()) {
                int n = b.readUnsignedByte();
                for (int i = 0; i < n; i++) {
                    int flag = b.readUnsignedByte();
                    String phone = ByteBufUtils.readByteLengthString(b, GBK);
                    String name = ByteBufUtils.readByteLengthString(b, GBK);
                    list.add(new Contact(flag, phone, name));
                }
            }
            return new PhoneBook(type, list);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(type);
            if (type == DELETE_ALL) {
                return;
            }
            out.writeByte(contacts.size());
            for (Contact c : contacts) {
                out.writeByte(c.flag);
                ByteBufUtils.writeByteLengthString(out, c.phone, GBK);
                ByteBufUtils.writeByteLengthString(out, c.name, GBK);
            }
        }

        public int getType() {
            return type;
        }

        public List<Contact> getContacts() {
            return Collections.unmodifiableList(contacts);
        }

        @Override
        public String toString() {
            return "PhoneBook{type=" + type + ", contacts=" + contacts + '}';
        }
    }

    public static final class Contact {
        public static final int IN = 1;
        public static final int OUT = 2;
        public static final int IN_OUT = 3;

        private final int flag;
        private final String phone;
        private final String name;

        public Contact(int flag, String phone, String name) {
            this.flag = flag;
            this.phone = phone;
            this.name = name;
        }

        public int getFlag() {
            return flag;
        }

        public String getPhone() {
            return phone;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name + ":" + phone + "(" + flag + ")";
        }
    }
}
