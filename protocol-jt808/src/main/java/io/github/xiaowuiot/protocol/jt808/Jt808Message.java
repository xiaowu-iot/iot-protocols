package io.github.xiaowuiot.protocol.jt808;

/**
 * 一条 808 消息：消息头 + 已解析的消息体。
 * <p>
 * 消息体的类型由消息 ID 决定，比如 0x0200 是 {@link io.github.xiaowuiot.protocol.jt808.body.Location}；
 * 心跳这类没有消息体的消息，body 为 null；还没支持的消息，body 是原始字节 {@code byte[]}。
 */
public class Jt808Message {

    private final Jt808Header header;
    private final Object body;

    /**
     * 分包消息还没收齐时为 true，此时 body 为 null，只需要回一个通用应答
     */
    private final boolean fragment;
    private final boolean malformed;

    public Jt808Message(Jt808Header header, Object body) {
        this(header, body, false, false);
    }

    private Jt808Message(Jt808Header header, Object body, boolean fragment, boolean malformed) {
        this.header = header;
        this.body = body;
        this.fragment = fragment;
        this.malformed = malformed;
    }

    public static Jt808Message fragment(Jt808Header header) {
        return new Jt808Message(header, null, true, false);
    }

    /**
     * 消息头没问题、消息体和协议对不上（多半是厂家固件和文档不一致）。平台要回"消息有误"，否则终端会一直重发
     */
    public static Jt808Message malformed(Jt808Header header) {
        return new Jt808Message(header, null, false, true);
    }

    public Jt808Header getHeader() {
        return header;
    }

    public int getMsgId() {
        return header.getMsgId();
    }

    @SuppressWarnings("unchecked")
    public <T> T getBody() {
        return (T) body;
    }

    public boolean isFragment() {
        return fragment;
    }

    public boolean isMalformed() {
        return malformed;
    }

    @Override
    public String toString() {
        return header + (fragment ? " (分包未收齐)" : malformed ? " (消息体有误)" : " " + body);
    }
}
