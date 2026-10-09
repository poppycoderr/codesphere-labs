# TCP 字节流上的消息边界

对应文章：[tcp-message-framing.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/tcp-message-framing.md)。

`src/FramingLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，客户端与服务端都在同一个进程里，通过回环地址上的真实 TCP 连接通信。各场景用固定的先后顺序（必要处加几百毫秒的等待）构造，使结果确定，`scripts/verify.sh` 把输出与预期逐行比较。

1. 发送方三次写出三条消息，接收方稍后读一次；
2. 一条消息分两段到达，接收方读一次；
3. 4 字节长度前缀加 `readFully`，在同样两种到达方式下读帧；
4. 换行分隔，消息内容里有换行；
5. 把 `GET ` 与 TLS 握手记录的前 4 个字节当作长度；带上限的接收方收到一个 HTTP 请求；
6. 同一条连接上先发慢请求再发快请求，响应按到达顺序配对与按请求编号配对；
7. 第一个请求读超时后，在同一条连接上发第二个请求；
8. 非阻塞通道一次写 64 MB，对端不读。

## 快速运行

```bash
make verify     # 需要 Docker；约 15 秒
make evidence
make clean
```
