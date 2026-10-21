package io.github.xiaowuiot.protocol.jt808.server;

import io.github.xiaowuiot.protocol.codec.command.PendingRequests;
import io.github.xiaowuiot.protocol.codec.session.Session;
import io.github.xiaowuiot.protocol.codec.session.SessionManager;
import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.HasReplySerial;
import io.github.xiaowuiot.protocol.jt808.body.GeneralResponse;
import io.github.xiaowuiot.protocol.jt808.body.InfoBodies;
import io.github.xiaowuiot.protocol.jt808.body.LocationQueryResponse;
import io.github.xiaowuiot.protocol.jt808.body.Multimedia;
import io.github.xiaowuiot.protocol.jt808.body.Recorder;
import io.github.xiaowuiot.protocol.jt808.body.Jt808Body;
import io.github.xiaowuiot.protocol.jt808.body.LinkBodies;
import io.github.xiaowuiot.protocol.jt808.body.StoredMultimedia;
import io.github.xiaowuiot.protocol.jt808.body.TerminalParams;
import io.github.xiaowuiot.protocol.jt808.codec.Jt808Subpackages;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.AttributeKey;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 平台下发指令，并等待终端应答。
 * <pre>
 * CompletableFuture&lt;Jt808Message&gt; f = sender.send("13800138000", MessageId.QUERY_PARAMS, null, 5, TimeUnit.SECONDS);
 * </pre>
 * 应答怎么对上号：下发时取一个流水号，终端的应答里会带回"应答流水号"，用"设备号 + 流水号"匹配。
 * 终端对不同指令的应答方式不同：大部分回通用应答 0x0001，查询参数回 0x0104，查询位置回 0x0201，
 * 它们的消息体第一个字段都是应答流水号。
 */
public class Jt808CommandSender {

    public static final String PROTOCOL = "jt808";

    /**
     * 会话里保存终端的消息头模板（版本、手机号），下发时按终端的版本组包
     */
    public static final String HEADER_TEMPLATE = "jt808.header";

    private static final String SENT_PACKETS = "jt808.sentPackets";

    /**
     * 每台终端最多保留几组发出的分包，用来响应补传请求
     */
    private static final int MAX_SENT_GROUPS = 4;

    /**
     * 下发指令 → 应答消息 ID，这些应答没有"应答流水号"字段，比如查询终端属性 0x8107 的应答 0x0107。
     * 只能按应答类型匹配，所以同一台终端同时只能有一个这类请求在等应答
     */
    private static final Map<Integer, Integer> NO_SERIAL_RESPONSES = new ConcurrentHashMap<>();

    /**
     * 扩展协议登记的应答消息 ID（1078 的 0x1003、0x1205 等）
     */
    private static final Set<Integer> EXTRA_RESPONSES = ConcurrentHashMap.newKeySet();

    static {
        NO_SERIAL_RESPONSES.put(MessageId.QUERY_ATTRIBUTES, MessageId.ATTRIBUTES_RESPONSE);
        // 0x0702 既是 0x8702 的应答，也是插拔卡时终端主动上报的消息，本身没有应答流水号
        NO_SERIAL_RESPONSES.put(MessageId.DRIVER_INFO_REQUEST, MessageId.DRIVER_INFO);
        NO_SERIAL_RESPONSES.put(MessageId.AREA_QUERY, MessageId.AREA_QUERY_RESPONSE);
    }

    /**
     * 登记扩展协议的应答消息。应答体实现了 {@link HasReplySerial} 的按流水号匹配
     */
    public static void registerResponse(int responseMsgId) {
        EXTRA_RESPONSES.add(responseMsgId);
    }

    /**
     * 登记没有应答流水号的扩展应答：按应答类型匹配
     */
    public static void registerNoSerialResponse(int requestMsgId, int responseMsgId) {
        NO_SERIAL_RESPONSES.put(requestMsgId, responseMsgId);
        EXTRA_RESPONSES.add(responseMsgId);
    }

    public static boolean isExtraResponse(int msgId) {
        return EXTRA_RESPONSES.contains(msgId);
    }

    private final SessionManager sessions;
    private final PendingRequests<Jt808Message> pending;

    public Jt808CommandSender(SessionManager sessions) {
        this(sessions, new PendingRequests<>());
    }

    public Jt808CommandSender(SessionManager sessions, PendingRequests<Jt808Message> pending) {
        this.sessions = sessions;
        this.pending = pending;
    }

    /**
     * 下发并等待应答。
     *
     * @param body 消息体，没有消息体的指令（比如查询参数）传 null
     * @return 终端的应答消息；设备不在线、发送失败、超时都会以异常结束
     */
    public CompletableFuture<Jt808Message> send(String deviceId, int msgId, Object body, long timeout, TimeUnit unit) {
        Session session = sessions.get(deviceId);
        if (session == null || !session.isActive()) {
            CompletableFuture<Jt808Message> f = new CompletableFuture<>();
            f.completeExceptionally(new DeviceOfflineException(deviceId));
            return f;
        }
        int serial = session.nextSerialNo();
        Jt808Header header = Jt808Header.to(session.<Jt808Header>getAttribute(HEADER_TEMPLATE), msgId, serial);
        // 应答里没有应答流水号的指令，只能按"等的是哪种应答"登记
        Integer noSerialResponse = NO_SERIAL_RESPONSES.get(msgId);
        int key = noSerialResponse != null ? typeKey(noSerialResponse) : serial;
        // 先登记再发送，否则终端应答得快的话，应答会比登记先到
        CompletableFuture<Jt808Message> future = pending.register(deviceId, key, timeout, unit);
        session.write(new Jt808Message(header, body)).addListener(f -> {
            if (!f.isSuccess()) {
                pending.fail(deviceId, key, f.cause());
            }
        });
        return future;
    }

    /**
     * 只发送，不等应答，比如回复终端的应答。
     */
    public boolean sendOnly(String deviceId, int msgId, Object body) {
        Session session = sessions.get(deviceId);
        if (session == null || !session.isActive()) {
            return false;
        }
        Jt808Header header = Jt808Header.to(session.<Jt808Header>getAttribute(HEADER_TEMPLATE), msgId, session.nextSerialNo());
        session.write(new Jt808Message(header, body));
        return true;
    }

    /**
     * 下发可能超过 1023 字节的消息（升级包、大段文本、多边形围栏），超长时自动分包。
     * <p>
     * 分包后每一包终端都会回通用应答，这里等的是<b>最后一包</b>的应答。发出去的分包在会话里留一份，
     * 终端用 0x0005 要求补传时按包序号重发，见 {@link #resend}。
     */
    public CompletableFuture<Jt808Message> sendLarge(String deviceId, int msgId, Jt808Body body, long timeout, TimeUnit unit) {
        Session session = sessions.get(deviceId);
        if (session == null || !session.isActive()) {
            CompletableFuture<Jt808Message> f = new CompletableFuture<>();
            f.completeExceptionally(new DeviceOfflineException(deviceId));
            return f;
        }
        Jt808Header template = Jt808Header.to(session.<Jt808Header>getAttribute(HEADER_TEMPLATE), msgId, 0);
        ByteBuf buf = Unpooled.buffer();
        byte[] bytes;
        try {
            body.encode(buf, template);
            bytes = ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
        if (bytes.length <= Jt808Header.MAX_BODY_LENGTH) {
            // 不用分包，走普通流程（应答的匹配方式也按普通指令来）
            return send(deviceId, msgId, body, timeout, unit);
        }
        List<Jt808Message> packets = Jt808Subpackages.split(template, bytes, Jt808Subpackages.DEFAULT_PACKET_SIZE,
                session::nextSerialNo);
        sentPackets(session).put(packets.get(0).getHeader().getSerialNo(), packets);
        int lastSerial = packets.get(packets.size() - 1).getHeader().getSerialNo();
        CompletableFuture<Jt808Message> future = pending.register(deviceId, lastSerial, timeout, unit);
        Upload upload = new Upload(session, packets, uploadWindow, uploadAckTimeoutMillis,
                e -> pending.fail(deviceId, lastSerial, e));
        future.whenComplete((r, e) -> upload.finish());
        upload.start();
        return future;
    }

    /** 分包下发的窗口：最多几包没收到应答，0 表示不限（一次全部写出） */
    private volatile int uploadWindow = 8;
    /** 一包等多久应答，超时就当它收到了继续发：有的终端只回最后一包的应答 */
    private volatile long uploadAckTimeoutMillis = 3000;

    /**
     * 分包下发的流控参数。升级包这种几百包的消息一口气写出去，很多终端会丢包甚至断开
     *
     * @param window          最多几包没收到应答，0 表示不限
     * @param ackTimeoutMillis 一包等应答的时间，超时就继续发下一包
     */
    public void setUploadFlowControl(int window, long ackTimeoutMillis) {
        this.uploadWindow = window;
        this.uploadAckTimeoutMillis = ackTimeoutMillis;
    }

    /**
     * 一组分包的发送进度：窗口里的包收到通用应答（或者等应答超时）才发下一包；连接写缓冲积压时暂停
     */
    private static final class Upload {
        private static final AttributeKey<Map<Integer, Upload>> KEY = AttributeKey.valueOf("jt808.uploads");

        final Session session;
        final List<Jt808Message> packets;
        final int window;
        final long ackTimeoutMillis;
        final java.util.function.Consumer<Throwable> onError;
        final java.util.Set<Integer> inflight = new java.util.HashSet<>();
        int next;
        boolean finished;

        Upload(Session session, List<Jt808Message> packets, int window, long ackTimeoutMillis,
               java.util.function.Consumer<Throwable> onError) {
            this.session = session;
            this.packets = packets;
            this.window = window <= 0 ? Integer.MAX_VALUE : window;
            this.ackTimeoutMillis = ackTimeoutMillis;
            this.onError = onError;
        }

        static Map<Integer, Upload> of(io.netty.channel.Channel ch) {
            Map<Integer, Upload> m = ch.attr(KEY).get();
            if (m == null) {
                m = new java.util.concurrent.ConcurrentHashMap<>();
                Map<Integer, Upload> old = ch.attr(KEY).setIfAbsent(m);
                if (old != null) {
                    m = old;
                }
            }
            return m;
        }

        void start() {
            runInLoop(this::pump);
        }

        void runInLoop(Runnable r) {
            io.netty.channel.EventLoop loop = session.getChannel().eventLoop();
            if (loop.inEventLoop()) {
                r.run();
            } else {
                loop.execute(r);
            }
        }

        /** 在连接的 IO 线程里执行 */
        void pump() {
            io.netty.channel.Channel ch = session.getChannel();
            if (finished || !ch.isActive()) {
                return;
            }
            while (next < packets.size() && inflight.size() < window) {
                if (!ch.isWritable()) {
                    // 写缓冲积压（网络慢）：等一会儿再发，不往内存里堆
                    ch.eventLoop().schedule(this::pump, 20, TimeUnit.MILLISECONDS);
                    return;
                }
                Jt808Message p = packets.get(next++);
                int serial = p.getHeader().getSerialNo();
                inflight.add(serial);
                of(ch).put(serial, this);
                ch.writeAndFlush(p).addListener(f -> {
                    if (!f.isSuccess()) {
                        onError.accept(f.cause());
                    }
                });
                ch.eventLoop().schedule(() -> acked(serial), ackTimeoutMillis, TimeUnit.MILLISECONDS);
            }
        }

        /** 收到这一包的通用应答，或者等应答超时 */
        void acked(int serial) {
            if (inflight.remove(serial)) {
                of(session.getChannel()).remove(serial, this);
                pump();
            }
        }

        void finish() {
            runInLoop(() -> {
                finished = true;
                Map<Integer, Upload> m = of(session.getChannel());
                for (Jt808Message p : packets) {
                    m.remove(p.getHeader().getSerialNo(), this);
                }
                inflight.clear();
            });
        }
    }

    /**
     * 终端补传分包请求 0x0005：按包序号重发之前发出的分包。找不到原始消息（太久了，已经被挤出缓存）就不处理
     */
    public void resend(Session session, LinkBodies.ResendRequest request) {
        List<Jt808Message> packets = sentPackets(session).get(request.getOriginalSerialNo());
        if (packets == null) {
            return;
        }
        for (Integer no : request.getPacketNos()) {
            if (no >= 1 && no <= packets.size()) {
                session.write(packets.get(no - 1));
            }
        }
    }

    /**
     * 每台终端最近发出的几组分包，按第一包的流水号保存
     */
    private static Map<Integer, List<Jt808Message>> sentPackets(Session session) {
        Map<Integer, List<Jt808Message>> map = session.getAttribute(SENT_PACKETS);
        if (map == null) {
            synchronized (session) {
                map = session.getAttribute(SENT_PACKETS);
                if (map == null) {
                    map = Collections.synchronizedMap(new LinkedHashMap<Integer, List<Jt808Message>>() {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<Integer, List<Jt808Message>> eldest) {
                            return size() > MAX_SENT_GROUPS;
                        }
                    });
                    session.setAttribute(SENT_PACKETS, map);
                }
            }
        }
        return map;
    }

    /**
     * 收到终端的应答类消息时调用。
     *
     * @return false 表示没有在等这条应答（已经超时了，或者是重复的应答）
     */
    public boolean onResponse(Session session, Jt808Message msg) {
        Integer replySerial = replySerialNo(msg.getBody());
        if (replySerial != null && msg.getBody() instanceof GeneralResponse) {
            // 分包下发中某一包的应答：推进发送窗口（最后一包的应答还要往下走，完成等待中的请求）
            Map<Integer, Upload> uploads = session.getChannel().attr(Upload.KEY).get();
            Upload u = uploads == null ? null : uploads.get(replySerial);
            if (u != null) {
                u.acked(replySerial);
            }
        }
        if (replySerial != null) {
            return pending.complete(session.getDeviceId(), replySerial, msg);
        }
        return pending.complete(session.getDeviceId(), typeKey(msg.getMsgId()), msg);
    }

    public int pendingCount() {
        return pending.size();
    }

    private static Integer replySerialNo(Object body) {
        if (body instanceof HasReplySerial) {
            return ((HasReplySerial) body).getReplySerialNo();
        }
        if (body instanceof GeneralResponse) {
            return ((GeneralResponse) body).getReplySerialNo();
        }
        if (body instanceof TerminalParams) {
            return ((TerminalParams) body).getReplySerialNo();
        }
        if (body instanceof LocationQueryResponse) {
            return ((LocationQueryResponse) body).getReplySerialNo();
        }
        if (body instanceof Multimedia.CameraResponse) {
            return ((Multimedia.CameraResponse) body).getReplySerialNo();
        }
        if (body instanceof InfoBodies.QuestionAnswer) {
            return ((InfoBodies.QuestionAnswer) body).getReplySerialNo();
        }
        if (body instanceof InfoBodies.VehicleControlResponse) {
            return ((InfoBodies.VehicleControlResponse) body).getReplySerialNo();
        }
        if (body instanceof Recorder.Upload) {
            return ((Recorder.Upload) body).getReplySerialNo();
        }
        if (body instanceof StoredMultimedia.QueryResponse) {
            return ((StoredMultimedia.QueryResponse) body).getReplySerialNo();
        }
        return null;
    }

    /**
     * 按应答类型等待时用的 key：流水号只有 0~65535，加上 0x10000 就不会和真实的流水号冲突
     */
    private static int typeKey(int responseMsgId) {
        return 0x10000 + responseMsgId;
    }

    public static class DeviceOfflineException extends RuntimeException {
        public DeviceOfflineException(String deviceId) {
            super("设备不在线：" + deviceId);
        }
    }
}
