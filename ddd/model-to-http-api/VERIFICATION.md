# 验证记录：从模型到 HTTP 接口：条件更新、命令与受理

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 两个客户端读到同一版本，各自 PUT 整体替换 | 都是 200，先到的修改（地址）丢失 |
| 两个客户端都带 `If-Match: "1"` | 第一个 200，第二个 412 |
| 不带 `If-Match` 的 PUT | 428 |
| PUT 只带 address | note 被清空为 null |
| PATCH 只带 address | note 保留 |
| PUT 把已发货订单的 status 写成 CANCELLED | 成功，规则被绕过 |
| `POST /orders/{id}/cancellation` | 409（已发货不能取消） |
| 重发同一个 POST，不带幂等键 | 新增 2 个订单 |
| 带同一个 `Idempotency-Key` 重发 | 两次返回同一个 Location，新增 1 个 |
| 生成报表 | 202，Location 指向状态资源，状态 RUNNING |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 版本检查在单个进程内用 `synchronized` 保护；多实例部署时要由数据库的条件更新（`WHERE version = ?`）保证。
- 幂等键存在内存里，没有过期、没有校验重发请求体是否相同；生产实现要把键与请求摘要、结果一起持久化。
- 状态资源只演示了 RUNNING，没有实现完成、失败与结果下载。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
