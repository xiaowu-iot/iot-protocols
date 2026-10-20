package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

/**
 * 消息体。编码时需要消息头，因为有些字段 2013 和 2019 版的长度不一样。
 */
public interface Jt808Body {

    void encode(ByteBuf out, Jt808Header header);
}
