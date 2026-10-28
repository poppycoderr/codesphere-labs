# 验证记录：RST 从哪来

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 连接无人监听的端口 | ECONNREFUSED |
| 服务端不读就 close | 客户端 ECONNRESET；服务端发 RST 1 个、FIN 0 个 |
| 服务端读完再 close | 客户端 EOF；FIN 1 个、RST 0 个 |
| SO_LINGER(on, 0) | 客户端 ECONNRESET；服务端无 TIME_WAIT |
| 对端关闭后连续写 3 次 | ok、EPIPE、EPIPE；服务端回 RST 1 个 |
| 空闲连接被服务端关闭后复用 | 发送成功、读到 EOF；服务端回 RST |
| 复用前 MSG_PEEK 检查 | 发现已关闭，新建连接得到 PONG |
| tcp[13] & 18 != 0 与 == 18 | 4 个与 1 个 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 内核是 Docker Desktop 的 linuxkit 7.0.14；「未读数据时 close 发 RST」是 RFC 2525 推荐、Linux 实现的行为，其他系统可能不同。
- Python 默认忽略 SIGPIPE，所以写已关闭的连接得到 EPIPE 错误；C 程序默认会被 SIGPIPE 信号终止，JVM 同样忽略它并抛出 IOException。
- 连接池场景用一问一答的最小协议模拟；真实 HTTP 客户端在读到 EOF 时可能自动重试幂等请求，也可能把它报告为错误。
- 抓包工具里的「重传」「乱序」等标签是分析器的启发式判断，本实验没有涉及。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
