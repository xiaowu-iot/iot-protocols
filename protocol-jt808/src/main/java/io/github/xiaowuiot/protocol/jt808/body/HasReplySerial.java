package io.github.xiaowuiot.protocol.jt808.body;

/**
 * 带"应答流水号"字段的应答消息体。扩展协议（1078 的资源列表 0x1205 等）实现这个接口，
 * 下发指令的调用方就能按流水号等到对应的应答。
 */
public interface HasReplySerial {

    int getReplySerialNo();
}
