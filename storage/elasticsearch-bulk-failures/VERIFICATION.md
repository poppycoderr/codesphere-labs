# 验证记录：bulk 的部分失败、重试与版本号

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 5 条里有类型错误与未声明字段 | HTTP 200，`errors=true`；o2 `document_parsing_exception`、o4 `strict_dynamic_mapping_exception`，索引里 3 条 |
| 带 `_id` 只重试失败的 2 条 | 都是 201，索引里 5 条 |
| 不指定 `_id` 整批重试 | B1、B3 各 2 条，B2 1 条 |
| `create` 遇到已存在的 `_id` | HTTP 200，c1 逐项 409，c2 201；c1 未被覆盖 |
| 不带版本号，旧消息后到 | 最终状态被旧消息覆盖为 paid |
| `version_type=external`，版本 2 在版本 3 之后到 | 409 拒绝，保持 shipped，`_version=3` |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 没有触发 429（`es_rejected_execution_exception`）这类可重试的逐项失败，它取决于写入线程池的队列与负载，需要在目标集群上观察。
- 单节点、零副本；副本写入失败与分片不可用时的逐项错误没有覆盖。
- 外部版本号要求源系统能给出单调递增的版本，本实验直接写死。

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
