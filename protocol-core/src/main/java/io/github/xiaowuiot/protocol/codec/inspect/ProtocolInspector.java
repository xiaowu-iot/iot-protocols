package io.github.xiaowuiot.protocol.codec.inspect;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 报文解析器：协议中心用来解析、构造报文。和网络、平台无关，每个协议模块各自实现一个。
 * <ul>
 *   <li>{@link #detect}：给一段字节，判断像不像这个协议，返回 0~100 的把握；</li>
 *   <li>{@link #inspect}：切帧、反转义、校验，解析成字段树，每个字段带字节位置；</li>
 *   <li>{@link #messages}：消息清单，哪些已经实现；</li>
 *   <li>{@link #templates} / {@link #build}：按模板填字段生成完整报文。</li>
 * </ul>
 */
public interface ProtocolInspector {

    /** 协议标识，比如 jt808 */
    String id();

    /** 显示名，比如 JT/T 808 */
    String name();

    /** 标准号和版本，比如 JT/T 808-2011 / 2013 / 2019 */
    String standard();

    /** 一句话说明：用在哪、传输方式 */
    String description();

    /** 0~100：这段字节是这个协议的把握 */
    int detect(byte[] data);

    /** 解析一段字节，里面可以有多帧（粘包），也可能只有半帧 */
    List<Frame> inspect(byte[] data);

    List<MessageInfo> messages();

    default List<BuildTemplate> templates() {
        return Collections.emptyList();
    }

    /**
     * 按模板生成一帧完整报文（含帧头尾、转义、校验）
     *
     * @param values 模板里各输入项的值
     */
    default byte[] build(String messageId, Map<String, String> values) {
        throw new UnsupportedOperationException(name() + " 还不支持构造报文");
    }
}
