package io.github.xiaowuiot.protocol.jt808.server;

import io.github.xiaowuiot.protocol.codec.server.TcpServer;
import io.github.xiaowuiot.protocol.codec.session.SessionManager;
import io.github.xiaowuiot.protocol.jt808.codec.Jt808Codecs;
import io.github.xiaowuiot.protocol.jt808.codec.Jt808Rsa;

/**
 * 808 接入服务。
 * <pre>
 * Jt808Server server = Jt808Server.builder().port(7611).listener(...).build();
 * server.start();
 * </pre>
 * 流水线：读空闲检测 → 切帧 / 转义 → 消息编解码 → 业务处理。线程模型、TCP 参数、启停顺序在 {@link TcpServer} 里，所有协议共用。
 */
public class Jt808Server {

    private final SessionManager sessions;
    private final Jt808CommandSender commandSender;
    private final TcpServer server;

    private Jt808Server(Builder b) {
        this.sessions = b.sessions != null ? b.sessions : new SessionManager();
        this.commandSender = new Jt808CommandSender(sessions);
        Jt808ServerHandler handler = new Jt808ServerHandler(sessions,
                b.authService != null ? b.authService : defaultAuthService(),
                commandSender,
                b.listener != null ? b.listener : Jt808MessageListener.NOOP);
        handler.setAuthTimeoutSeconds(b.authTimeoutSeconds);
        this.server = new TcpServer("808 接入服务", Jt808CommandSender.PROTOCOL, b.port, b.idleSeconds, b.workerThreads,
                pipeline -> {
                    if (b.rsa != null) {
                        pipeline.channel().attr(Jt808Rsa.KEY).set(b.rsa);
                    }
                    Jt808Codecs.addTo(pipeline);
                    pipeline.addLast("handler", handler);
                },
                () -> sessions.closeAll(Jt808CommandSender.PROTOCOL));
        this.server.setBindAddress(b.bindAddress);
        this.server.setConnectionLimits(b.maxConnections, b.maxConnectionsPerIp);
    }

    /**
     * 没传鉴权服务时用内存版：任何手机号注册都放行，只适合本地调试和测试，生产环境必须接自己的终端档案
     */
    private static Jt808AuthService defaultAuthService() {
        org.slf4j.LoggerFactory.getLogger(Jt808Server.class).warn("没有配置 Jt808AuthService，使用 InMemoryAuthService：任何终端都能注册鉴权，只能用于调试和测试");
        return new InMemoryAuthService();
    }

    public static Builder builder() {
        return new Builder();
    }

    public void start() throws InterruptedException {
        server.start();
    }

    public void stop() {
        server.stop();
    }

    public int getPort() {
        return server.getPort();
    }

    /**
     * 网络层统计、原始报文记录
     */
    public TcpServer getTcpServer() {
        return server;
    }

    public SessionManager getSessions() {
        return sessions;
    }

    public Jt808CommandSender getCommandSender() {
        return commandSender;
    }

    public static class Builder {
        private int port = 7611;
        private String bindAddress;
        private int idleSeconds = 180;
        private int workerThreads = 0;
        private SessionManager sessions;
        private Jt808AuthService authService;
        private Jt808MessageListener listener;
        private int authTimeoutSeconds = 60;
        private int maxConnections;
        private int maxConnectionsPerIp;
        private Jt808Rsa rsa;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 只监听某个地址，不设置时监听所有网卡
         */
        public Builder bindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
            return this;
        }

        /**
         * 读空闲超时，建议设为心跳间隔的 3 倍
         */
        public Builder idleSeconds(int idleSeconds) {
            this.idleSeconds = idleSeconds;
            return this;
        }

        /**
         * IO 线程数，0 表示 Netty 默认值（CPU 核数 × 2）
         */
        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        /**
         * 多个协议共用一个会话管理器时传进来，否则各自新建
         */
        public Builder sessions(SessionManager sessions) {
            this.sessions = sessions;
            return this;
        }

        public Builder authService(Jt808AuthService authService) {
            this.authService = authService;
            return this;
        }

        public Builder listener(Jt808MessageListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * 连上之后多少秒内必须完成鉴权，默认 60 秒，0 表示不限
         */
        public Builder authTimeoutSeconds(int authTimeoutSeconds) {
            this.authTimeoutSeconds = authTimeoutSeconds;
            return this;
        }

        /**
         * 连接数上限，0 表示不限。终端走运营商专网时很多车共用出口 IP，单 IP 上限要按现场设
         */
        public Builder connectionLimits(int maxConnections, int maxPerIp) {
            this.maxConnections = maxConnections;
            this.maxConnectionsPerIp = maxPerIp;
            return this;
        }

        /**
         * 支持 RSA 加密的消息（消息体属性 bit10）：终端发 0x0A00 上报公钥后，平台回 0x8A00 平台公钥。不设置时收到加密消息会丢弃
         */
        public Builder rsa(Jt808Rsa rsa) {
            this.rsa = rsa;
            return this;
        }

        public Jt808Server build() {
            return new Jt808Server(this);
        }
    }
}
