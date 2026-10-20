package io.github.xiaowuiot.protocol.jt1078.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 所有流的登记处：谁在推、谁在看，什么时候该让终端停止推流。
 * <p>
 * <b>按需拉流 + 引用计数</b>：一路流不管多少人看，终端只推一份；最后一个观看者离开后等一个宽限期（默认 30 秒），
 * 期间没人再来看，就通知平台下发 0x9102 关闭——用户切换页面、刷新时不会让终端反复停推、重推。
 * <p>
 * 推流鉴权和"该不该关"都由平台决定（{@link Authorizer}、{@link Listener}），这里只管流本身。
 */
public class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    public interface Listener {
        /**
         * 宽限期结束仍然没有观看者：平台该下发 0x9102 让终端停推了
         */
        void onIdle(String key);

        default void onPublish(String key) {
        }

        default void onUnpublish(String key) {
        }
    }

    public interface Authorizer {
        /**
         * 终端推流时：只接收平台下发过 0x9101 / 0x9201 的流，其余连接直接断开
         */
        boolean allowPublish(String key);

        /**
         * 浏览器拉流时：校验平台签发的播放令牌
         */
        boolean allowPlay(String key, String token);
    }

    private final Map<String, MediaStream> streams = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> idleTimers = new ConcurrentHashMap<>();
    private final Map<String, Long> waitingSince = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-hub");
        t.setDaemon(true);
        return t;
    });
    private final int graceSeconds;
    private final int waitSeconds;
    private volatile Listener listener = key -> {
    };
    private volatile Authorizer authorizer = new Authorizer() {
        @Override
        public boolean allowPublish(String key) {
            return true;
        }

        @Override
        public boolean allowPlay(String key, String token) {
            return true;
        }
    };

    /**
     * @param graceSeconds 最后一个观看者离开后多久关流
     * @param waitSeconds  观看者等终端推流最多等多久，超时断开
     */
    public StreamHub(int graceSeconds, int waitSeconds) {
        this.graceSeconds = graceSeconds;
        this.waitSeconds = waitSeconds;
        timer.scheduleWithFixedDelay(this::sweep, 5, 5, TimeUnit.SECONDS);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    public Authorizer authorizer() {
        return authorizer;
    }

    // ==================== 推流 ====================

    public MediaStream publish(String key) {
        MediaStream s = streams.computeIfAbsent(key, MediaStream::new);
        s.setPublishing(true);
        waitingSince.remove(key);
        log.info("开始推流 {}", key);
        listener.onPublish(key);
        if (s.subscriberCount() == 0) {
            // 有可能观看者已经走了（终端连上来很慢），也按没人看处理
            scheduleIdle(key);
        }
        return s;
    }

    public void unpublish(String key, MediaStream s) {
        if (streams.get(key) != s) {
            return;
        }
        s.setPublishing(false);
        log.info("停止推流 {}，共 {} 帧", key, s.frames());
        listener.onUnpublish(key);
        if (s.subscriberCount() == 0) {
            remove(key);
        } else {
            // 还有人在看：终端可能只是断线重连，给它一点时间
            waitingSince.put(key, System.currentTimeMillis());
        }
    }

    // ==================== 播放 ====================

    public void subscribe(String key, MediaStream.Subscriber sub) {
        MediaStream s = streams.computeIfAbsent(key, MediaStream::new);
        cancelIdle(key);
        if (!s.isPublishing()) {
            waitingSince.putIfAbsent(key, System.currentTimeMillis());
        }
        s.addSubscriber(sub);
        log.info("{} 开始观看 {}，当前 {} 人", sub.id(), key, s.subscriberCount());
    }

    public void unsubscribe(String key, MediaStream.Subscriber sub) {
        MediaStream s = streams.get(key);
        if (s == null) {
            return;
        }
        s.removeSubscriber(sub);
        log.info("{} 停止观看 {}，剩 {} 人", sub.id(), key, s.subscriberCount());
        if (s.subscriberCount() == 0) {
            if (s.isPublishing()) {
                scheduleIdle(key);
            } else {
                remove(key);
            }
        }
    }

    private void scheduleIdle(String key) {
        cancelIdle(key);
        idleTimers.put(key, timer.schedule(() -> {
            idleTimers.remove(key);
            MediaStream s = streams.get(key);
            if (s != null && s.subscriberCount() == 0) {
                log.info("{} 已经 {} 秒没人看，关闭", key, graceSeconds);
                try {
                    listener.onIdle(key);
                } catch (RuntimeException e) {
                    log.warn("关闭流 {} 失败", key, e);
                }
            }
        }, graceSeconds, TimeUnit.SECONDS));
    }

    private void cancelIdle(String key) {
        ScheduledFuture<?> f = idleTimers.remove(key);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void remove(String key) {
        cancelIdle(key);
        waitingSince.remove(key);
        MediaStream s = streams.remove(key);
        if (s != null) {
            s.close();
        }
    }

    /**
     * 观看者等了很久终端都没推上来（终端不在线、网络不通），断开观看者，页面上提示失败
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : new ArrayList<>(waitingSince.entrySet())) {
            MediaStream s = streams.get(e.getKey());
            if (s == null || s.isPublishing()) {
                waitingSince.remove(e.getKey());
            } else if (now - e.getValue() > waitSeconds * 1000L) {
                log.info("{} 等了 {} 秒没有推流，断开观看者", e.getKey(), waitSeconds);
                remove(e.getKey());
                listener.onIdle(e.getKey());
            }
        }
    }

    public MediaStream get(String key) {
        return streams.get(key);
    }

    public Collection<MediaStream> streams() {
        return streams.values();
    }

    public void shutdown() {
        timer.shutdownNow();
        streams.keySet().forEach(this::remove);
    }
}
