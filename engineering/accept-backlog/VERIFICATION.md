# 验证记录：监听队列

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `listen(5)`，不 accept，同时 40 个连接，2 秒后 | 握手完成 6 个，34 个仍在等待 |
| 内核计数器 | `ListenOverflows` 与 `ListenDrops` 都增加 |
| 在握手完成的连接上发请求 | `send` 返回 36 字节；读取 1 秒无响应 |
| 队列满后再来一个连接，连接超时 1 秒 | connect 超时 |
| `listen(1000)`，`somaxconn = 8` | 握手完成 9 个，31 个仍在等待 |
| Java `new ServerSocket(port)`，不 accept，80 个连接 | 握手完成 51 个 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 结果来自 Docker Desktop 虚拟机里的 Linux 内核（版本见 `environment.txt`），回环地址。「backlog + 1」是这个内核的表现，不同内核版本对队列已满时的处理细节不同。
- 只观察到 2 秒这个时间点。等待中的连接之后会随着 SYN 重传发生变化，实验没有跟踪这一段；`tcp_abort_on_overflow = 1` 的行为在回环上没有得到稳定的结果，没有归档。
- 没有经过真实网络、负载均衡器或服务网格，它们各自还有连接队列与超时。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
