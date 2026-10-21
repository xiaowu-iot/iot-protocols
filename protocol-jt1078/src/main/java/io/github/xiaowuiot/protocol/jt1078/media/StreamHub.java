package io.github.xiaowuiot.protocol.jt1078.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 所有流的登记处：谁在推、谁在看，什么时候该让终端停止推流。
 * <p>
 * <b>按需拉流 + 引用计数</b>：一路流不管多少人看，终端只推一份；最后一个观看者离开后等一个宽限期（默认 30 秒），
 * 期间没人再来看，就通知平台下发 0x9102 关闭——用户切换页面、刷新时不会让终端反复停推、重推。
 * <p>
 * <b>一路流只认一个推流者</b>：同一个 key 来了新的推流连接（终端重连、回放换了一段录像），旧连接直接踢掉；
 * 旧连接断开时只有它还是当前推流者才算"停止推流"，不会把新连接的流关掉。
 * <p>
 * 推流鉴权和"该不该关"都由平台决定（{@link Authorizer}、{@link Listener}），这里只管流本身。
 * 状态变化（推流、停推、观看、离开、空闲、超时）都在同一把锁里做，频率很低，不影响收帧的性能。
 */
public class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    public interface Listener {
        /**
         * 宽限期结束仍然没有观看者，或者观看者等推流超时：平台该下发 0x9102 / 0x9202 让终端停推了。
         * 调用之前这路流已经从登记处删掉，终端再推上来的数据会被丢弃、连接会被断开
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

    /** 没配置时一律拒绝：媒体端口对外开放，不能谁都能推、谁都能看 */
    private static final Authorizer DENY_ALL = new Authorizer() {
        @Override
        public boolean allowPublish(String key) {
            return false;
        }

        @Override
        public boolean allowPlay(String key, String token) {
            return false;
        }
    };

    private static final class Publisher {
        final Object owner;
        final Runnable kick;

        Publisher(Object owner, Runnable kick) {
            this.owner = owner;
            this.kick = kick;
        }
    }

    private final Map<String, MediaStream> streams = new ConcurrentHashMap<>();
    private final Map<String, Publisher> publishers = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> idleTimers = new HashMap<>();
    private final Map<String, Long> waitingSince = new HashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-hub");
        t.setDaemon(true);
        return t;
    });
    private final int graceSeconds;
    private final int waitSeconds;
    private volatile Listener listener = key -> {
    };
    private volatile Authorizer authorizer = DENY_ALL;
    private final List<Consumer<MediaStream>> publishHooks = new CopyOnWriteArrayList<>();

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

    /**
     * 终端开始推流（含断线重连后重新推）时回调，比如 RTMP 转推。和 {@link Listener} 分开：
     * Listener 归平台用，这个归媒体节点自己用，集群部署时媒体节点单独跑也能用
     */
    public void addPublishHook(Consumer<MediaStream> hook) {
        publishHooks.add(hook);
    }

    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    public Authorizer authorizer() {
        return authorizer;
    }

    // ==================== 推流 ====================

    /**
     * 开始推流
     *
     * @param owner 推流者（一般是推流连接），停推时用它确认是不是同一个
     * @param kick  被新推流者顶替、或者流被关掉时怎么断开它
     */
    public synchronized MediaStream publish(String key, Object owner, Runnable kick) {
        Publisher old = publishers.put(key, new Publisher(owner, kick));
        if (old != null && old.owner != owner) {
            log.info("{} 有新的推流连接，断开旧的", key);
            safely(old.kick, key);
        }
        MediaStream s = streams.computeIfAbsent(key, MediaStream::new);
        s.setPublishing(true);
        waitingSince.remove(key);
        log.info("开始推流 {}", key);
        safely(() -> listener.onPublish(key), key);
        for (Consumer<MediaStream> hook : publishHooks) {
            safely(() -> hook.accept(s), key);
        }
        if (s.subscriberCount() == 0) {
            // 有可能观看者已经走了（终端连上来很慢），也按没人看处理
            scheduleIdle(key);
        }
        return s;
    }

    /**
     * 推流连接断开。不是当前推流者（已经被新连接顶替）时什么都不做
     */
    public synchronized void unpublish(String key, Object owner) {
        Publisher p = publishers.get(key);
        if (p == null || p.owner != owner) {
            return;
        }
        publishers.remove(key);
        MediaStream s = streams.get(key);
        if (s == null) {
            return;
        }
        s.setPublishing(false);
        log.info("停止推流 {}，共 {} 帧", key, s.frames());
        safely(() -> listener.onUnpublish(key), key);
        if (s.subscriberCount() == 0) {
            remove(key);
        } else {
            // 还有人在看：终端可能只是断线重连，给它一点时间（超过 waitSeconds 由 sweep 断开观看者）
            waitingSince.put(key, System.currentTimeMillis());
        }
    }

    /**
     * 平台主动结束一路流（比如回放放完了）：断开观看者和推流者，不再通知 onIdle
     */
    public synchronized void close(String key) {
        Publisher p = publishers.remove(key);
        remove(key);
        if (p != null) {
            safely(p.kick, key);
        }
    }

    // ==================== 播放 ====================

    public synchronized void subscribe(String key, MediaStream.Subscriber sub) {
        MediaStream s = streams.computeIfAbsent(key, MediaStream::new);
        cancelIdle(key);
        if (!s.isPublishing()) {
            waitingSince.putIfAbsent(key, System.currentTimeMillis());
        }
        s.addSubscriber(sub);
        log.info("{} 开始观看 {}，当前 {} 人", sub.id(), key, s.subscriberCount());
    }

    /**
     * 听这路流的声音。声音跟着画面走：没人看画面的流不能单独听（不算观看人数，画面关了声音一起断）
     */
    public synchronized void subscribeAudio(String key, MediaStream.Subscriber sub) {
        MediaStream s = streams.get(key);
        if (s == null) {
            sub.close();
            return;
        }
        s.addAudioSubscriber(sub);
    }

    public synchronized void unsubscribeAudio(String key, MediaStream.Subscriber sub) {
        MediaStream s = streams.get(key);
        if (s != null) {
            s.removeAudioSubscriber(sub);
        }
    }

    public synchronized void unsubscribe(String key, MediaStream.Subscriber sub) {
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
        idleTimers.put(key, timer.schedule(() -> onIdleTimer(key), graceSeconds, TimeUnit.SECONDS));
    }

    private synchronized void onIdleTimer(String key) {
        idleTimers.remove(key);
        MediaStream s = streams.get(key);
        // 锁里再看一次：定时任务开始执行的同时可能有人刚点了播放
        if (s == null || s.subscriberCount() > 0) {
            return;
        }
        log.info("{} 已经 {} 秒没人看，关闭", key, graceSeconds);
        // 先从登记处删掉：0x9102 发出后到终端真正断开前，有人点播放会重新走 0x9101，不会挂在一路要关的流上
        Publisher p = publishers.remove(key);
        remove(key);
        safely(() -> listener.onIdle(key), key);
        if (p != null) {
            safely(p.kick, key);
        }
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
    private synchronized void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : new ArrayList<>(waitingSince.entrySet())) {
            String key = e.getKey();
            MediaStream s = streams.get(key);
            if (s == null || s.isPublishing()) {
                waitingSince.remove(key);
            } else if (now - e.getValue() > waitSeconds * 1000L) {
                log.info("{} 等了 {} 秒没有推流，断开观看者", key, waitSeconds);
                publishers.remove(key);
                remove(key);
                // 这个定时任务一旦抛异常就再也不会执行，回调必须兜住
                safely(() -> listener.onIdle(key), key);
            }
        }
    }

    private static void safely(Runnable r, String key) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("流 {} 的回调处理失败", key, e);
        }
    }

    public MediaStream get(String key) {
        return streams.get(key);
    }

    public Collection<MediaStream> streams() {
        return streams.values();
    }

    public synchronized void shutdown() {
        timer.shutdownNow();
        new ArrayList<>(streams.keySet()).forEach(this::remove);
        publishers.clear();
    }
}
