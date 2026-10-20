package io.github.xiaowuiot.protocol.codec.inspect;

/**
 * 协议消息清单里的一条：消息 ID、名称、方向、平台是否已经能解析（或构造）
 */
public class MessageInfo {

    private final String id;
    private final String name;
    private final String direction;
    private final boolean implemented;
    /** 有没有构造模板（报文构造页面能不能直接生成） */
    private final boolean buildable;

    public MessageInfo(String id, String name, String direction, boolean implemented, boolean buildable) {
        this.id = id;
        this.name = name;
        this.direction = direction;
        this.implemented = implemented;
        this.buildable = buildable;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDirection() {
        return direction;
    }

    public boolean isImplemented() {
        return implemented;
    }

    public boolean isBuildable() {
        return buildable;
    }
}
