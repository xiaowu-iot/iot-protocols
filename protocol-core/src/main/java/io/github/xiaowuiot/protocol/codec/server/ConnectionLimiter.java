package io.github.xiaowuiot.protocol.codec.server;

import io.github.xiaowuiot.protocol.codec.util.RateLimitedLog;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 连接数上限：总数和单个 IP 的连接数。超过的新连接直接断开。
 * <p>
 * 为什么要有：接入端口对公网开放，有人开几万条连接什么都不发（或者只发心跳不鉴权），就能把文件句柄和内存占光，
 * 真终端反而连不上。
 * <p>
 * 单个 IP 的上限要谨慎：很多终端走运营商的物联网卡专网（APN），出口是同一个或几个 IP，
 * 几百上千台车共用一个出口 IP 是正常的。所以默认不限，按现场情况再配。
 */
@ChannelHandler.Sharable
public class ConnectionLimiter extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionLimiter.class);
    private static final RateLimitedLog REJECT_LOG = new RateLimitedLog(log, 5000);
    private static final AttributeKey<String> COUNTED = AttributeKey.valueOf("iot.limiter.ip");

    private final String name;
    private final int maxConnections;
    private final int maxPerIp;
    private final AtomicInteger total = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private final LongAdder rejected = new LongAdder();

    /**
     * @param maxConnections 总连接数上限，0 表示不限
     * @param maxPerIp       单个 IP 的连接数上限，0 表示不限
     */
    public ConnectionLimiter(String name, int maxConnections, int maxPerIp) {
        this.name = name;
        this.maxConnections = maxConnections;
        this.maxPerIp = maxPerIp;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String ip = ip(ctx.channel().remoteAddress());
        int n = total.incrementAndGet();
        AtomicInteger c = perIp.computeIfAbsent(ip, k -> new AtomicInteger());
        int m = c.incrementAndGet();
        ctx.channel().attr(COUNTED).set(ip);
        if ((maxConnections > 0 && n > maxConnections) || (maxPerIp > 0 && m > maxPerIp)) {
            rejected.increment();
            REJECT_LOG.warn(name + " 连接数超过上限，断开新连接",
                    ip + "（总数 " + n + "/" + maxConnections + "，这个 IP " + m + "/" + maxPerIp + "）", null);
            ctx.close();
            return;
        }
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String ip = ctx.channel().attr(COUNTED).getAndSet(null);
        if (ip != null) {
            total.decrementAndGet();
            perIp.computeIfPresent(ip, (k, v) -> v.decrementAndGet() <= 0 ? null : v);
        }
        ctx.fireChannelInactive();
    }

    private static String ip(SocketAddress a) {
        if (a instanceof InetSocketAddress) {
            InetSocketAddress i = (InetSocketAddress) a;
            return i.getAddress() != null ? i.getAddress().getHostAddress() : i.getHostString();
        }
        return String.valueOf(a);
    }

    public int connections() {
        return total.get();
    }

    public long rejected() {
        return rejected.sum();
    }
}
