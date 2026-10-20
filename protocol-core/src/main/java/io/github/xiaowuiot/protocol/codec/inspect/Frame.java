package io.github.xiaowuiot.protocol.codec.inspect;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析出来的一帧：消息 ID 和名称、方向、反转义后的完整字节（字段的 offset 都相对它）、字段树、发现的问题和提示。
 */
public class Frame {

    private String messageId;
    private String messageName;
    /** up 终端 → 平台，down 平台 → 终端，空表示看不出来 */
    private String direction;
    /** 反转义之后整帧的十六进制 */
    private String hex;
    /** 原始（转义前）十六进制 */
    private String rawHex;
    private final List<Field> fields = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    /** 提示：不是错误，但看报文时需要知道的（比如有转义、版本是猜的） */
    private final List<String> notes = new ArrayList<>();

    public Field add(Field f) {
        fields.add(f);
        return f;
    }

    public Frame problem(String p) {
        problems.add(p);
        return this;
    }

    public Frame note(String n) {
        notes.add(n);
        return this;
    }

    public String getMessageId() {
        return messageId;
    }

    public Frame setMessageId(String messageId) {
        this.messageId = messageId;
        return this;
    }

    public String getMessageName() {
        return messageName;
    }

    public Frame setMessageName(String messageName) {
        this.messageName = messageName;
        return this;
    }

    public String getDirection() {
        return direction;
    }

    public Frame setDirection(String direction) {
        this.direction = direction;
        return this;
    }

    public String getHex() {
        return hex;
    }

    public Frame setHex(String hex) {
        this.hex = hex;
        return this;
    }

    public String getRawHex() {
        return rawHex;
    }

    public Frame setRawHex(String rawHex) {
        this.rawHex = rawHex;
        return this;
    }

    public List<Field> getFields() {
        return fields;
    }

    public List<String> getProblems() {
        return problems;
    }

    public List<String> getNotes() {
        return notes;
    }
}
