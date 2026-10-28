# 验证记录：对端还活着吗

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 默认参数 | 空闲 7200 秒后探测，间隔 75 秒，9 次；tcp_retries2 = 15 |
| 断开后空闲 / 只开 SO_KEEPALIVE / 写入后重传 | 10 秒内都没有错误 |
| keepalive 2/1/3 | 约 5 秒 ETIMEDOUT |
| TCP_USER_TIMEOUT=3000ms | 约 3 秒 ETIMEDOUT |
| 对端进程冻结，短 keepalive | 8 秒内没有错误 |
| 应用层心跳 | 1 秒超时 |
| 什么也不发的连接 | 无首包超时：一直挂着；2 秒首包超时：2 秒被关闭 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 网络断开用客户端命名空间里的 iptables 丢包模拟，不是真实的链路中断或对端宕机；对端宕机重启后会对旧连接回 RST，那种情况能更早发现。
- 默认参数下 keepalive 要两个多小时、写入重传约 15 分钟才放弃，实验只观察了 10 秒，这两个时长来自内核文档，没有等满。
- Python 把内核返回的 ETIMEDOUT 与 settimeout 等满都表示为 TimeoutError，实验按 errno 区分两者。

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
