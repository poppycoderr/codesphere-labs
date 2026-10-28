# 验证记录：小包为什么等了 40 毫秒

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 写、写、读（Nagle 开启） | 中位数 30—60ms（本次约 44ms）；50 个请求 100 个数据包 |
| TCP_NODELAY | 中位数小于 5ms；100 个数据包 |
| 合成一次 sendall | 中位数小于 5ms；50 个数据包 |
| sendmsg 一次提交两段 | 中位数小于 5ms；50 个数据包 |
| tcp_autocorking | 1 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 两个容器在同一台宿主机上，网络往返只有几十微秒，40ms 的延迟几乎全部来自延迟确认；跨机房时还要加上真实的往返时间。
- Linux 在连接刚建立时处于快速确认模式，前几个请求不受影响，所以先预热 20 个。
- 延迟确认的时长由内核动态调整（最小 40ms），不同内核与 TCP_QUICKACK 设置下会不同；服务端开启 TCP_QUICKACK 的效果没有测。

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
