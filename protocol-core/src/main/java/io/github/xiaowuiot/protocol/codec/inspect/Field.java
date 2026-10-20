package io.github.xiaowuiot.protocol.codec.inspect;

import java.util.ArrayList;
import java.util.List;

/**
 * 报文解析出来的一个字段：在帧里的字节位置、原始十六进制、解释后的值，可以有子字段（结构体、列表）。
 * <p>
 * offset 是相对整帧（反转义之后）的位置；-1 表示这个字段是从解码后的对象里读出来的，没有精确的字节位置。
 */
public class Field {

    private final String name;
    private final int offset;
    private final int length;
    private final String hex;
    private String value;
    private String problem;
    private final List<Field> children = new ArrayList<>();

    public Field(String name, int offset, int length, String hex, String value) {
        this.name = name;
        this.offset = offset;
        this.length = length;
        this.hex = hex;
        this.value = value;
    }

    public Field add(Field child) {
        children.add(child);
        return child;
    }

    /** 这个字段有问题（校验不对、长度不符、取值超范围） */
    public Field problem(String problem) {
        this.problem = problem;
        return this;
    }

    public Field value(String value) {
        this.value = value;
        return this;
    }

    public String getName() {
        return name;
    }

    public int getOffset() {
        return offset;
    }

    public int getLength() {
        return length;
    }

    public String getHex() {
        return hex;
    }

    public String getValue() {
        return value;
    }

    public String getProblem() {
        return problem;
    }

    public List<Field> getChildren() {
        return children;
    }
}
