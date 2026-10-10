# 序列化的版本兼容

对应文章：[serialization-compatibility.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/serialization-compatibility.md)。

`src/SerdeLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。发送方与接收方用的是同一份数据结构的两个版本。

- JSON 一侧同时用 Jackson 2.22.3（`com.fasterxml.jackson`）与 Jackson 3.2.3（`tools.jackson`）的默认配置，两者包名不同，放在同一个类路径上。
- Protobuf 一侧用 protobuf-java 4.36.2，消息的描述符在程序里构造，用 `DynamicMessage` 读写，不需要 `protoc`。新旧两个版本就是两份描述符。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 20 秒
make evidence
make clean
```
