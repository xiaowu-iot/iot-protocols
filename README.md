# iot-protocols

物联网和车联网协议库：纯 Java + Netty，**不依赖 Spring，兼容 JDK 8**，可以直接嵌入现有系统。

| 模块 | 协议 | 说明 |
|---|---|---|
| `protocol-core` | — | 底座：转义帧 / 长度帧 / ASCII 帧切帧，半包、粘包、垃圾数据处理，会话管理，指令应答匹配，TCP 服务，收发统计和原始报文记录；**与协议无关的报文解析器接口** |
| `protocol-jt808` | JT/T 808-2011 / 2013 / 2019 | 消息头、分包合包、全部标准消息的编解码，位置附加信息，终端接入服务 |
| `protocol-jt1078` | JT/T 1078-2016 | 音视频信令；码流解包（SIM 卡号 6 / 10 字节自动识别）、帧重组、HTTP-FLV 转封装、声音分发、RTMP 转推 |
| `protocol-hj212` | HJ 212-2005 / 2017 / 2025 | 实时和历史数据、反控命令、CRC；2025 版 Flag 识别、SM4 解密、心跳 |
| `protocol-slp` | SLP（示例） | 一个虚构的智能储物柜私有协议，演示怎样从零设计私有二进制协议，见 [PROTOCOL.md](protocol-slp/PROTOCOL.md) |

## 报文解析器

每个协议模块都带一个 `ProtocolInspector`，通过 `META-INF/services` 登记，引了哪些模块就能解析哪些协议：

```java
ProtocolInspectors inspectors = ProtocolInspectors.load();
byte[] data = ProtocolInspectors.parseHex("7E 02 00 ... 7E");
String protocol = inspectors.detect(data).get(0).getId();   // 自动识别，比如 jt808
List<Frame> frames = inspectors.get(protocol).inspect(data);  // 切帧、反转义、校验，输出字段树
```

字段树里每个字段都带字节位置、原始十六进制、解释后的值和发现的问题（长度不对、校验错误……），适合做报文调试工具；
`templates()` / `build()` 可以按模板填字段生成完整报文（帧头尾、转义、校验都算好）。

下图是 jt-platform 里用它做的报文解析页：左边字段树带字节位置，右边点字节反查字段。

![报文解析](docs/screenshots/jt-proto-inspect.jpg)

## 构建

```bash
git clone https://github.com/xiaowu-iot/iot-protocols.git
cd iot-protocols
mvn clean install
```

JDK 8 以上都能构建，产物在 JDK 8 上运行。

## 使用

还没有发布到 Maven 中央仓库，可以先 `mvn install` 到本地，或者通过 [JitPack](https://jitpack.io) 引用：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.xiaowu-iot.iot-protocols</groupId>
    <artifactId>protocol-jt808</artifactId>
    <version>版本号（tag）</version>
</dependency>
```

0.x 版本接口可能还会调整，1.0 之后保持兼容。

## 工作流程

下面是各协议在这个库里的处理流程，类名、方法名和代码一致。图用 Mermaid 写，GitHub 上直接显示成图。

### 1. 总体分层

所有协议共用同一套底座：一条 TCP 连接进来，先经过底座的公共环节，再进各协议自己的切帧、解码和业务处理；平台下发指令走反方向，用"流水号 / 应答匹配"把异步的应答对回到调用方。

```mermaid
flowchart TB
    D["设备 / 终端 / 下级平台"]

    subgraph UP["上行：设备 → 平台"]
        direction TB
        S["TcpServer（epoll / NIO）"] --> I["IdleStateHandler：读空闲判离线"]
        I --> ST["IoStats：连接数、流量"]
        ST --> RAW["RawMessageRecorder：原始报文，每台最近 20 条"]
        RAW --> F["协议切帧：转义帧 / 长度帧 / ASCII 帧"]
        F --> M["消息解码：校验、消息头、消息体"]
        M --> H["协议服务 Handler：注册鉴权、应答、会话"]
        H --> L["Listener：交给平台 / 网关"]
    end

    subgraph DOWN["下行：平台 → 设备"]
        direction TB
        CS["CommandSender.send"] --> PR["PendingRequests：按 设备号+流水号 先登记，带超时"]
        PR --> E["消息编码、转义、加校验"]
        E --> W["写出"]
        R["设备应答"] --> PR2["按流水号 / 消息类型对回"]
        PR2 --> OK["CompletableFuture 完成（或超时失败）"]
    end

    D -->|TCP 字节流| S
    W --> D
    H -->|是应答| R
    H --> SM["SessionManager：设备号 → 连接"]
```

底座的几条通用规则：

- **坏数据不断连接**：校验错、长度不对的帧丢掉并计数（`CodecStats`），日志限流，连接保留。
- **半包粘包**：切帧器只认完整的帧，不完整的留在缓冲区里等下一次读；帧长有上限，超长直接丢。
- **会话**：鉴权（或第一条合法数据）之后才有会话；同一设备号在新连接上线时关闭旧连接。212 默认一条连接只认第一个通过校验的 MN，换了 MN 的数据直接丢弃（防冒用），可以关掉。
- **连接防护**：可以配总连接数、单个 IP 连接数的上限，超过的新连接直接断开；808 连上之后限定时间（默认 60 秒）内不鉴权就断开。
- **应答匹配**：下发时先登记再发送，应答按"设备号 + 流水号"（或按消息类型）对回；超时、发送失败都会让调用方立刻拿到结果。

### 2. JT/T 808

#### 2.1 终端接入时序

```mermaid
sequenceDiagram
    autonumber
    participant T as 终端
    participant H as Jt808ServerHandler
    participant A as Jt808AuthService
    participant SM as SessionManager
    participant L as Listener（平台）

    T->>H: TCP 连接
    Note over H: sessions.connected 发出"连接"事件
    Note over H: 开始计时：60 秒内没鉴权成功就断开
    T->>H: 0x0100 终端注册
    H->>A: register（车牌、厂商、终端 ID）
    A-->>H: 结果 + 鉴权码
    H-->>T: 0x8100 注册应答（带鉴权码）
    T->>H: 0x0102 终端鉴权
    H->>A: authenticate
    alt 鉴权码正确
        H->>SM: register（设备号 → 连接，旧连接被踢下线）
        H-->>T: 0x8001 通用应答 成功
    else 鉴权码不对
        H-->>T: 0x8001 通用应答 失败
    end
    loop 心跳间隔
        T->>H: 0x0002 心跳
        H-->>T: 0x8001
    end
    T->>H: 0x0200 位置汇报
    H->>L: onMessage
    L-->>H: 处理完成（CompletionStage）
    H-->>T: 0x8001 成功（处理失败不回，终端会重发）
    Note over T,H: 读空闲超过 3 个心跳周期：判定离线，关闭连接，发"断开"事件（原因：心跳超时）
```

#### 2.2 上行解码流水线

```mermaid
flowchart TD
    A["TCP 字节流"] --> B["EscapedDelimiterFrameDecoder<br/>按 7E 切帧，7D 02→7E、7D 01→7D 反转义"]
    B -->|超长 / 转义非法| X1["丢弃并计数"]
    B --> C["Jt808MessageDecoder：BCC 校验"]
    C -->|校验失败| X1
    C --> D["读消息头"]
    D --> E{"消息体属性 bit14<br/>是 2019 版？"}
    E -->|是| V19["按 2019 版：协议版本号 + 10 字节手机号"]
    E -->|否| V13{"这条连接<br/>记过 2011 版？"}
    V13 -->|是| V11["按 2011 版"]
    V13 -->|否| V1["按 2013 版"]
    V19 --> F["消息体长度和属性一致？"]
    V11 --> F
    V1 --> F
    F -->|否| X1
    F --> G{"分包？"}
    G -->|否| J["BodyDecoders 按消息 ID 解消息体"]
    G -->|是，连接还没鉴权| X1
    G -->|是| SA["SubpackageAssembler<br/>每连接最多 4 组、8MB，60 秒过期"]
    SA -->|没收齐| FR["分包标记 → 每一包回 0x8001"]
    SA -->|收齐| J
    J -->|成功| K["Jt808Message → Handler"]
    J -->|字段和协议不符| MAL["消息有误标记 → 回 0x8001 结果 2"]
```

版本识别补充：2011 和 2013 版的消息头完全一样，只能靠注册消息 0x0100 的长度区分（2011 版的制造商 ID、终端型号更短）。认出 2011 版后记在连接上，之后这条连接的消息都按 2011 版解；终端重连后直接鉴权的，按注册时记在档案里的版本处理。

RSA 加密（消息体属性 bit10）：终端用 0x0A00 上报自己的公钥，平台回 0x8001 并用 0x8A00 下发平台公钥；之后终端发来的加密消息体用平台私钥解开，平台下发的消息可以选择用终端公钥加密（0x8A00 本身不加密）。RSA 1024、PKCS#1 填充，明文按 117 字节分块；标准没写死分块方式，对接前和终端厂家核对。平台密钥对要持久化保存。

#### 2.3 平台下发指令

```mermaid
flowchart TD
    A["业务调用 Jt808CommandSender.send<br/>（设备号、消息 ID、消息体、超时）"] --> B{"设备在线？"}
    B -->|否| X["立刻失败：DeviceOfflineException"]
    B -->|是| C["会话分配流水号"]
    C --> D["PendingRequests.register<br/>先登记再发送"]
    D --> E{"消息体超过 1023 字节？"}
    E -->|否| F["编码一帧 → 转义 → 发送"]
    E -->|是| G["sendLarge：自动分包，按窗口发送<br/>（默认同时 8 包在途，收到 0x0001 再发后面的）<br/>每包缓存，供终端补传"]
    F --> W{"等应答"}
    G --> W
    W -->|0x0001 通用应答<br/>按应答流水号匹配| OK["future 完成：结果码"]
    W -->|0x0104 / 0x0201 / 0x0107 等<br/>按消息类型匹配| OK
    W -->|超时| TO["future 失败：TimeoutException"]
    G -. 终端发 0x0005 补传请求 .-> R["按包序号重发缺的分包"]
```

### 3. JT/T 1078 音视频

#### 3.1 组件

信令走 808 的连接，音视频走单独的媒体端口，两条路在平台的 `VideoService` 汇合。

```mermaid
flowchart TB
    UI["① 浏览器：点播放"] -->|"POST /api/video/play"| VS["② 平台 VideoService<br/>流标识、播放令牌、登记期待推流"]
    VS -->|"③ 0x9101 实时 / 0x9201 回放（走 808 连接）"| T["终端"]
    T -->|"④ RTP 码流（实时 7615 / 回放 7616）"| PD

    subgraph MEDIA["媒体服务 MediaServer"]
        direction TB
        PD["Jt1078PacketDecoder<br/>找帧头 30 31 63 64，识别 SIM 卡号 6 / 10 字节"] --> IH["IngestHandler<br/>推流鉴权，登记推流者"]
        IH --> FA["FrameAssembler<br/>分包重组成帧（视频、音频分开拼）"]
        FA --> MS["MediaStream<br/>拆 NAL、序列头、封装 FLV、GOP 缓存、分发"]
        MS --> HTTP["HttpFlvHandler（7617）<br/>校验播放令牌，chunked 输出 FLV"]
        IH -.->|"publish / unpublish"| HUB["StreamHub<br/>谁在推、谁在看、引用计数<br/>宽限期 30 秒、等推流 20 秒"]
        HUB -.->|"开始推流（可选）"| RTMP["RtmpPublisher<br/>旁听观看者，不算人数"]
        HTTP -.->|"subscribe / unsubscribe"| HUB
    end

    HTTP -->|"⑤ HTTP-FLV"| PL["浏览器 mpegts.js 播放"]
    RTMP -->|"RTMP（可选）"| ZLM["ZLMediaKit / SRS / CDN<br/>再转 HLS、WebRTC、RTSP"]
    HUB -.-> IDLE["⑥ 没人看了（宽限期到）或等不到推流：<br/>StreamHub.onIdle → VideoService 通过 808 下发 0x9102 / 0x9202 关闭"]
```

#### 3.2 实时视频时序

```mermaid
sequenceDiagram
    autonumber
    participant B as 浏览器
    participant VS as 平台 VideoService
    participant T as 终端
    participant MS as 媒体服务
    participant HUB as StreamHub

    B->>VS: 点播放（车辆、通道、主/子码流）
    VS->>VS: key = 手机号去前导 0 + "_" + 通道号，签发（或复用）播放令牌
    alt 这路流已经在推
        VS-->>B: 播放地址（alreadyStreaming）
    else 没在推
        VS->>VS: 登记"期待推流"（2 分钟内有效）
        VS->>T: 0x9101 实时音视频请求（媒体服务地址、端口、通道、码流）
        T-->>VS: 0x0001 通用应答
        VS-->>B: 播放地址 /live/{key}.flv?token
    end
    B->>MS: GET 播放地址
    MS->>VS: allowPlay（令牌、key、有效期）
    MS->>HUB: subscribe（取消空闲计时，没推流就记下开始等的时间）
    MS-->>B: FLV 文件头（有序列头和 GOP 就马上补发：秒开）
    T->>MS: 连接媒体端口，推 RTP 包
    MS->>VS: allowPublish（期待推流，或者有人在等的断线重连）
    MS->>HUB: publish（登记推流者，旧推流连接被踢掉）
    loop 每个 RTP 包
        MS->>MS: 重组成帧 → FLV tag → 发给每个观看者
    end
    MS-->>B: FLV 视频 tag（慢的观看者跳到下一个关键帧）
    B->>MS: 关闭页面（连接断开）
    MS->>HUB: unsubscribe，观看人数变成 0
    Note over HUB: 宽限期 30 秒内有人再来就继续看
    HUB->>HUB: 30 秒后仍没人看：从登记处删除这路流
    HUB->>VS: onIdle
    VS->>T: 0x9102 关闭音视频传输
    Note over MS: 终端还在推的数据会被丢弃，推流连接被断开
```

#### 3.3 一路流的生命周期

```mermaid
stateDiagram-v2
    state "等终端推流" as Waiting
    state "推流中、有人看" as Playing
    state "推流中、没人看（宽限期）" as NoViewer
    state "推流断开、有人在等" as Reconnect
    state "关闭" as Closed

    [*] --> Waiting: 浏览器先来（subscribe）
    [*] --> NoViewer: 终端先推上来（publish，没人看）
    Waiting --> Playing: 终端推流（publish）
    Waiting --> Closed: 等了 20 秒没推上来（sweep，触发 onIdle）
    Playing --> NoViewer: 最后一个观看者离开
    NoViewer --> Playing: 宽限期内有人再来
    NoViewer --> Closed: 宽限期 30 秒到（onIdle → 0x9102）
    Playing --> Reconnect: 推流连接断开
    Reconnect --> Playing: 终端重连推流（允许，不用重发 0x9101）
    Reconnect --> Closed: 20 秒没重连上
    Playing --> Closed: 回放放完（_pb 流推流断开）
    Closed --> [*]
```

#### 3.4 一帧的处理（MediaStream.onFrame）

```mermaid
flowchart TD
    A["FrameAssembler 交出一帧"] --> B{"视频？"}
    B -->|音频| X["G.711A/U、ADPCM 解成 16 位 PCM；AAC 去掉海思头原样转发<br/>发给 /live/{流}.audio 的收听者（写不进去就丢这一块）"]
    B -->|视频| C["H264.split：按起始码拆 NAL"]
    C --> D["FlvMuxer.sort：分拣 SPS / PPS / VPS 和图像 NAL"]
    D --> E["时间戳：包里是显示时间 PTS，减去首帧时间<br/>有 B 帧时按最大回退量估算重排延迟：DTS = PTS − 延迟（严格递增），CTS = PTS − DTS<br/>倒退超过 1 秒或跳变超过 10 秒，按上一帧 +40ms 续上"]
    E --> F{"SPS / PPS 变了？<br/>（H.265 还要有 VPS）"}
    F -->|是| G["重建序列头（AVC / HEVC），发给所有观看者"]
    F -->|否| H
    G --> H["封装 FLV 视频 tag"]
    H --> I{"关键帧？"}
    I -->|是| J["清空 GOP 缓存，从这一帧开始重新缓存"]
    I -->|否| K["追加到 GOP（超过 300 帧或 16MB 整组清空）"]
    J --> L["分发：每个观看者一份引用（retainedDuplicate，不拷贝）"]
    K --> L
    L --> M{"观看者写缓冲超过高水位？"}
    M -->|是| N["这个观看者跳过后面的帧，等下一个关键帧"]
    M -->|否| O["写出"]
```

#### 3.5 录像回放的不同之处

- 先用 0x9205 查终端里的录像，终端用 0x1205 回资源列表（可能分包）。
- 回放下发 0x9201（推到回放端口 7616），流标识后面加 `_pb`，和同一通道的实时视频分开。
- 每次点回放都重新下发 0x9201；同一个 `_pb` 有新的推流连接时，旧的被踢掉。
- 终端放完这段录像会断开推流连接，平台随即结束这路流；没人看时下发 0x9202 停止回放。

#### 3.6 RTMP 转推（可选）

需要 HLS、WebRTC、RTSP，或者观众很多要走 CDN 时，不自己实现这些协议，把流用 RTMP 推给 ZLMediaKit、SRS 或 CDN，由它们分发。RTMP 客户端是自己写的（Netty，不引入新依赖）：

```java
RtmpRelay relay = RtmpRelay.template("rtmp://127.0.0.1:1935/live/{key}?secret=xxx");
hub.addPublishHook(relay::attach);   // 终端开始推流时自动转推，流关闭时跟着停
```

```mermaid
sequenceDiagram
    autonumber
    participant HUB as StreamHub
    participant P as RtmpPublisher
    participant S as ZLMediaKit / SRS
    participant MS as MediaStream

    HUB->>P: 终端开始推流（publish hook）
    P->>S: TCP 连接，简单握手 C0 C1 → S0 S1 S2 → C2
    P->>S: Set Chunk Size 4096，connect（app、tcUrl）
    S-->>P: _result
    P->>S: releaseStream、FCPublish、createStream
    S-->>P: _result（消息流 ID）
    P->>S: publish（流名带鉴权参数）
    S-->>P: onStatus NetStream.Publish.Start
    P->>MS: 以"旁听"身份加入（不算观看人数）
    MS-->>P: 序列头 + 整组画面（GOP），对端马上能出图
    loop 每一帧
        MS-->>P: FLV tag
        P->>S: 视频（类型 9）/ 音频（类型 8），数据就是 FLV tag 的数据部分
    end
    Note over P,S: 对端断开：2、4、8……最长 30 秒重连，重连后重新从关键帧开始
    MS-->>P: 流关闭（没人看、终端停推）→ 断开，不再重连
```

- 声音：G.711 原样发（FLV 编码 7 / 8，ZLMediaKit 支持），ADPCM 先解码再转成 G.711A，AAC 去掉 ADTS 头、先发 AudioSpecificConfig
- 转推跟着平台的按需拉流走：平台里有人看、终端在推才会转推；转推本身不让流一直开着
- 对端收得慢时丢帧到下一个关键帧，不影响平台自己的观看者
- 已和 ZLMediaKit（2026-10 master 版）联调：识别为 H.264 + PCMA，FLV、HLS 都能拉到，对端重启后几秒内自动重连。网页里的 FLV / HLS 播放器不认 G.711，播放 ZLMediaKit 的 FLV 时要设 `hasAudio: false`，要声音走 WebRTC；也可以 `RtmpRelay.setAudio(false)` 只推画面

### 4. HJ 212 污染物在线监测

```mermaid
sequenceDiagram
    autonumber
    participant D as 数采仪（现场机）
    participant S as Hj212Server
    participant C as Hj212CommandSender
    participant L as 平台

    D->>S: TCP 连接（没有登录步骤）
    D->>S: 2011 实时数据（## + 4 位长度 + 数据段 + CRC）
    Note over S: CRC 不对时按 MN 查密钥，SM4 解开 CP 后重新校验（2025 版加密报文）
    S->>S: 校验 PW，第一条合法数据时上线，记下 PW、ST、版本、密钥
    S->>L: onPacket
    S-->>D: 9014 数据应答（Flag 要求应答时，按设备的版本回）
    D->>S: 9015 心跳（2025 版）
    S-->>D: 9013
    L->>C: 下发命令（比如 2061 提取小时数据）
    C->>D: 2061（QN 严格递增，Flag 要求应答，2025 版加密 CP）
    alt overTime 秒没收到 9011
        C->>D: 原样重发（QN 不变），最多 reCount 次
    end
    D-->>C: 9011 请求应答（QnRtn=1 准备执行）
    D-->>C: 2061 数据（2017 版同一个 QN，2025 版每包新 QN）
    C-->>D: 9014（2025 版每包都回）
    D-->>C: 9012 执行结果（ExeRtn=1 成功）
    C->>L: 结果 + 收到的数据
```

- 分包：2017 版按 MN / QN / CN 组包，PNUM 有上限，每台设备同时最多 8 组；2025 版逐包交出。每一包都回 9014。
- 2025 版加密：数据命令（2000~2999）、1014 更新密钥、3020 的 CP 用 SM4 加密，长度和 CRC 按明文算。
- 校时：现场机发 1013 请求校时，平台回 9013 后下发 1012，重发 1012 时换成当前时间。

### 5. SLP 智能储物柜 + 固件升级

```mermaid
sequenceDiagram
    autonumber
    participant D as 储物柜
    participant S as SlpServer
    participant F as ChunkProvider
    participant L as 平台

    D->>S: 0x01 登录（设备 ID、固件版本、格口数）
    S-->>D: 0x81 登录应答（结果、平台时间、心跳间隔）
    loop 心跳间隔
        D->>S: 0x02 心跳
        S-->>D: 0x82
    end
    D->>S: 0x03 格口状态
    S->>L: onMessage
    S-->>D: 0x83 结果
    L->>S: 0x20 升级通知（版本、大小、CRC32）
    S->>D: 0x20
    D-->>S: 0xA0 应答
    loop 设备要哪片给哪片（断点续传的状态在设备上）
        D->>S: 0x21 分片请求（版本、偏移、长度）
        S->>F: chunk（默认没有固件：回"版本不存在"）
        S-->>D: 0xA1 分片
        S->>L: 升级进度
    end
    D->>S: 0x22 升级结果
    S->>L: onMessage
    S-->>D: 0xA2
```

帧格式：`AA 55 | 版本 | 命令 | 流水号 | 设备 ID 8 | 长度 | 数据 | CRC16`，详见 [PROTOCOL.md](protocol-slp/PROTOCOL.md)。升级分片从 `SlpServer.ChunkProvider` 取，接自己的固件仓库实现这个接口就行。

### 6. 报文解析器（协议中心）

每个协议模块都有一个 `ProtocolInspector`，在 `META-INF/services` 登记。平台的"协议中心"用它解析和构造报文。

```mermaid
flowchart LR
    A["粘贴的十六进制<br/>（空格、换行、0x、抓包偏移列都可以）"] --> B["ProtocolInspectors.parseHex"]
    B --> C["detect：每个解析器打分 0~100"]
    C --> D["选分数最高的（或用户指定的）解析器"]
    D --> E["inspect：切帧、反转义、校验"]
    E --> F["FieldReader 边读边记字节位置"]
    F --> G["字段树：名称、位置、十六进制、解释后的值、问题"]
    H["模板 + 表单输入"] --> I["build：组包、转义、算校验"] --> E
```

## 用这套协议库做的平台

### 车辆智能监控平台（jt-platform）

JT/T 808 全系列车辆监控平台，808、1078、809、主动安全、GB/T 32960、HJ 1239 的接入都用这个协议库。平台本身不开源，完整版源码在知识星球「小物学长的物联网实战营」：<https://t.zsxq.com/hldoY>

**综合大屏**：车辆分布、实时报警、里程排行、主动安全抓拍

![综合大屏](docs/screenshots/jt-screen.jpg)

**业务场景大屏**：同一套大屏框架，按场景换左右两栏和指标，大屏左上角可以切换

| 环卫收运：点位收运进度、漏收 | 公交：线路模拟图、到站准点率 |
|---|---|
| ![环卫收运大屏](docs/screenshots/jt-screen-waste.jpg) | ![公交大屏](docs/screenshots/jt-screen-bus.jpg) |
| **急救：进行中的任务、各环节用时** | **校车：乘车人数、到站准点（不显示学生姓名）** |
| ![急救大屏](docs/screenshots/jt-screen-ambulance.jpg) | ![校车大屏](docs/screenshots/jt-screen-school-bus.jpg) |

| 实时监控 | 实时视频（1078，多画面） |
|---|---|
| ![实时监控](docs/screenshots/jt-monitor.jpg) | ![实时视频](docs/screenshots/jt-video.jpg) |
| **轨迹回放：超速分段着色、停留点** | **主动安全：ADAS / DSM 报警和附件** |
| ![轨迹回放](docs/screenshots/jt-track.jpg) | ![主动安全](docs/screenshots/jt-adas.jpg) |

### 环境监测平台（env-monitor）

HJ212 环境在线监测平台，开源基础版在 <https://github.com/xiaowu-iot/env-monitor>，截图和在线演示见那个仓库。

## 许可证

[Apache License 2.0](LICENSE)
