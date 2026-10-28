# 验证记录：TIME_WAIT 与端口复用

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 服务端先关闭 / 客户端先关闭 | TIME_WAIT 分别只在服务端 / 客户端 |
| TIME_WAIT 剩余时间 | 50—60 秒；tcp_fin_timeout = 60 管的是 FIN_WAIT_2 |
| 重启绑定（原监听 / 新套接字的 SO_REUSEADDR） | 0/0、0/1 EADDRINUSE；1/1 成功 |
| 端口正被监听时 SO_REUSEADDR | EADDRINUSE |
| 两个 SO_REUSEPORT 套接字 | 都绑定成功，40 个连接两边都有 |
| 内核参数 | 无 tcp_tw_recycle；tcp_tw_reuse = 2 |
| 临时端口 10 个 | 1 个被 Docker DNS 监听占用；连同一目的地 9 次后 EADDRNOTAVAIL |
| 换一个目的端口 | 立即成功，本地端口仍在范围内 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 内核是 Docker Desktop 的 linuxkit 7.0.14；不同发行版的默认参数（tcp_tw_reuse、端口范围）可能不同。
- 同时关闭（双方同时发 FIN、都进入 TIME_WAIT）没有复现。
- tcp_tw_reuse=1 时客户端复用 TIME_WAIT 端口的行为没有测，容器内的 /proc/sys 只读，只能在启动时通过 sysctls 设置。

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
