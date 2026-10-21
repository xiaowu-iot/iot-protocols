package io.github.xiaowuiot.protocol.jt1078.media.rtmp;

import io.github.xiaowuiot.protocol.jt1078.media.MediaStream;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * RTMP 转推管理：终端开始推流时，按地址模板把这路流转推出去；流关闭时转推跟着结束。
 * <pre>
 * 终端 ─1078─▶ MediaServer ─HTTP-FLV─▶ 浏览器（平台自己的页面）
 *                    └─RTMP─▶ ZLMediaKit / SRS / CDN ─▶ HLS、WebRTC、RTSP、大量观众
 * </pre>
 * 转推跟着平台的按需拉流走：平台里有人看、终端在推，才会转推；没人看了，终端停推，转推也停。
 * 地址模板里的 {key} 换成流标识（SIM 卡号_通道，回放加 _pb），比如 rtmp://127.0.0.1:1935/live/{key}
 */
public class RtmpRelay {

    private static final Logger log = LoggerFactory.getLogger(RtmpRelay.class);

    private final Function<String, String> urlOf;
    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final Map<String, RtmpPublisher> publishers = new ConcurrentHashMap<>();

    /**
     * @param urlOf 流标识 → 转推地址，返回 null 表示这路流不转推（比如只转推实时视频、不转推回放）
     */
    public RtmpRelay(Function<String, String> urlOf) {
        this.urlOf = urlOf;
    }

    /** 按模板生成地址：{key} 换成流标识 */
    public static RtmpRelay template(String template) {
        return new RtmpRelay(key -> template.replace("{key}", key));
    }

    /**
     * 终端开始推流时调用。同一路流已经在转推（终端断线重连）就不重复建
     */
    public void attach(MediaStream stream) {
        String key = stream.getKey();
        RtmpPublisher old = publishers.get(key);
        if (old != null && !old.isStopped()) {
            return;
        }
        String url = urlOf.apply(key);
        if (url == null || url.isEmpty()) {
            return;
        }
        RtmpPublisher p;
        try {
            p = new RtmpPublisher(stream, url, group);
        } catch (IllegalArgumentException e) {
            log.warn("{} 不转推：{}", key, e.getMessage());
            return;
        }
        publishers.put(key, p);
        stream.onClose(() -> publishers.remove(key, p));
        p.start();
    }

    public void stop(String key) {
        RtmpPublisher p = publishers.remove(key);
        if (p != null) {
            p.stop();
        }
    }

    public RtmpPublisher get(String key) {
        return publishers.get(key);
    }

    public List<RtmpPublisher> list() {
        return new ArrayList<>(publishers.values());
    }

    public void shutdown() {
        publishers.values().forEach(RtmpPublisher::stop);
        publishers.clear();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
}
