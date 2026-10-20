package io.github.xiaowuiot.protocol.jt1078;

import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.codec.BodyDecoders;
import io.github.xiaowuiot.protocol.jt808.server.Jt808CommandSender;

/**
 * 在 808 上登记 1078 的信令：消息体解析器，以及哪些是应答消息（下发指令后要等它们）。
 */
public final class Jt1078Extension {

    private static volatile boolean registered;

    private Jt1078Extension() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        BodyDecoders.register(Jt1078Bodies.REALTIME_REQUEST, Jt1078Bodies.RealtimeRequest::decode);
        BodyDecoders.register(Jt1078Bodies.REALTIME_CONTROL, Jt1078Bodies.RealtimeControl::decode);
        BodyDecoders.register(Jt1078Bodies.AV_ATTRIBUTES, Jt1078Bodies.AvAttributes::decode);
        BodyDecoders.register(Jt1078Bodies.RESOURCE_QUERY, Jt1078Bodies.ResourceQuery::decode);
        BodyDecoders.register(Jt1078Bodies.RESOURCE_LIST, Jt1078Bodies.ResourceList::decode);
        BodyDecoders.register(Jt1078Bodies.PLAYBACK_REQUEST, Jt1078Bodies.PlaybackRequest::decode);
        BodyDecoders.register(Jt1078Bodies.PLAYBACK_CONTROL, Jt1078Bodies.PlaybackControl::decode);
        MessageId.registerName(Jt1078Bodies.REALTIME_REQUEST, "实时音视频传输请求");
        MessageId.registerName(Jt1078Bodies.REALTIME_CONTROL, "实时音视频传输控制");
        MessageId.registerName(Jt1078Bodies.AV_ATTRIBUTES_QUERY, "查询音视频属性");
        MessageId.registerName(Jt1078Bodies.AV_ATTRIBUTES, "上传音视频属性");
        MessageId.registerName(Jt1078Bodies.RESOURCE_QUERY, "查询资源列表");
        MessageId.registerName(Jt1078Bodies.RESOURCE_LIST, "上传音视频资源列表");
        MessageId.registerName(Jt1078Bodies.PLAYBACK_REQUEST, "远程录像回放请求");
        MessageId.registerName(Jt1078Bodies.PLAYBACK_CONTROL, "远程录像回放控制");
        // 0x1205 带应答流水号；0x1003 没有，按类型匹配
        Jt808CommandSender.registerResponse(Jt1078Bodies.RESOURCE_LIST);
        Jt808CommandSender.registerNoSerialResponse(Jt1078Bodies.AV_ATTRIBUTES_QUERY, Jt1078Bodies.AV_ATTRIBUTES);
        registered = true;
    }
}
