# 验证记录：SDK 自带重试与应用层重试叠加

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| SDK 默认值 | `DEFAULT_MAX_RETRIES=2`；超时 connect 5 秒，read、write、pool 600 秒 |
| 503，只用 SDK | 3 个请求，`x-stainless-retry-count` 为 0、1、2 |
| 应用层再包 3 次 | 9 个请求 |
| SDK `max_retries=0` + 应用层 3 次 | 3 个请求 |
| 409 / 400 | 3 个请求 / 1 个请求 |
| 503 + `x-should-retry: false` | 1 个请求 |
| 429 + `retry-after: 1` | 3 个请求，总耗时不少于 2 秒 |
| 服务端 1 秒响应，SDK `timeout=0.3` | 3 个请求都到达服务端，抛出 `APITimeoutError`；服务端把 3 个都处理完了 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 openai Python SDK 3.35.1；其他语言的 SDK、其他厂商的 SDK 默认重试次数与可重试状态码不同，以各自文档为准。
- 退避间隔只验证了「不少于 retry-after」，没有测量具体的抖动分布。
- 假服务不计费；真实服务里，超时后服务端处理完的请求是否计费取决于提供方。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
