# 验证记录：Elasticsearch 别名切换与在线迁移

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| reindex 后直接切别名 | v2 少 50、多 20、内容不同 30 |
| 按源版本号重放两遍 + 迟到旧版本 | 第二遍 101 条冲突被忽略，两边一致 |
| 不带版本号重放 | 1 条被旧版本覆盖 |
| 别名指向两个索引、没有写索引 | 写入 400 |
| 设了写索引 | 写入落在 v2，按 id 查得到 2 条 |
| 一个 _aliases 请求 remove + add | 原子切换；回切同样只改别名 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 变更在 reindex 完成之后执行，等价于「reindex 期间的写入」但不依赖时序；真实迁移里变更来自源系统的变更记录（更新时间或 CDC）。
- 删除的追平依赖版本号墓碑，墓碑默认只保留 index.gc_deletes（60 秒）；本实验在此时间内完成。
- 单节点、0 副本，没有测量 reindex 的速度，也没有验证 slices 与降副本。

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
