# iot-protocols

物联网和车联网协议库：纯 Java + Netty，**不依赖 Spring，兼容 JDK 8**，可以直接嵌入现有系统。

| 模块 | 协议 | 说明 |
|---|---|---|
| `protocol-core` | — | 底座：转义帧 / 长度帧 / ASCII 帧切帧，半包、粘包、垃圾数据处理，会话管理，指令应答匹配，TCP 服务，收发统计和原始报文记录；**与协议无关的报文解析器接口** |
| `protocol-jt808` | JT/T 808-2011 / 2013 / 2019 | 消息头、分包合包、全部标准消息的编解码，位置附加信息，终端接入服务 |
| `protocol-jt1078` | JT/T 1078-2016 | 音视频信令；码流解包（SIM 卡号 6 / 10 字节自动识别）、帧重组、HTTP-FLV 转封装 |
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

## 许可证

[Apache License 2.0](LICENSE)
