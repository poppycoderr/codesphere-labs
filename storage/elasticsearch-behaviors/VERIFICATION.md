# 验证记录：Elasticsearch 的几个行为

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **分词**：`standard` 把「Elasticsearch 9 支持向量检索 in production」切成 `elasticsearch、9、支、持、向、量、检、索、in、production`，中文按单字切；`english` 把「Running searches quickly」切成 `run、search、quickli`。
2. **text 与 keyword**：动态映射把字符串映射成 `text` 加 `keyword` 子字段（`ignore_above: 256`）；`term` 查 `title` 得 0 条，查 `title.keyword` 得 1 条；对 `title` 做 terms 聚合返回 400（`Fielddata is disabled on [title]`）。
3. **路由**：3 分片索引按 `_id` 写入 40 个文档，分布为 18、14、8；再以 `routing=user-42` 写入 10 个文档，带同样路由搜索时 `_shards.total=1`，返回 28 条，其中 user-42 的只有 10 条——路由只缩小范围，不是过滤。
4. **近实时**：`refresh_interval=30s` 时，写入后立即搜索 0 条，按 ID `GET` 找得到，手动 `_refresh` 后 1 条；`?refresh=wait_for` 写入阻塞了 29,962 ms（等到下一次自然刷新），响应里没有 `forced_refresh`，紧接着就能搜到；`?refresh=true` 的响应里 `forced_refresh=true`。
5. **terms 聚合误差**（21,531 个文档，真实前 5：A 3000、B 2500、C 2000、brand-long-33 414、brand-long-09 410）：
   - 5 分片、默认 `shard_size`（`size × 1.5 + 10` = 17）：第 4、5 名为 brand-long-09 344、brand-long-33 340，名次颠倒、计数各少了约 70；`doc_count_error_upper_bound=370`，`sum_other_doc_count=13,347`，返回桶的误差上界最大为 75；
   - `shard_size=1000`：与真实结果一致，误差上界 0；
   - 单分片索引、默认 `shard_size`：与真实结果一致，误差上界 0。
6. **默认 search 线程池**（容器 2 CPU）：`size=4`（`int(2 × 3 / 2) + 1`），`queue_size=4000`（1000 × 线程数）。
7. **打满线程池**（`size=2`、`queue_size=10`）：120 个请求中 4 个返回 200、116 个返回 429，线程池 `rejected` 增加 587——一个请求打到 5 个分片，被拒绝的是分片级任务；429 的原因是 `es_rejected_execution_exception`，`queue capacity = 10`。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/version.txt`](evidence/version.txt)。

## 三、执行步骤

见 `scripts/verify.sh`：默认配置启动并运行 `basics`，叠加 `compose.pool.yaml` 重建容器后运行 `pool`（重建后重新写入聚合数据）。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 聚合误差的具体名次取决于数据和文档在分片间的分布；实验固定了随机种子和 `_id`，结果可复现。换一份数据，默认 `shard_size` 下可能漏掉某个品牌，也可能只是计数偏少。
- 线程池实验里 200 与 429 的比例取决于请求到达的时机，断言只要求出现 429，且 `rejected` 的增量多于 429 的请求数。
- 单节点、无副本；多节点时协调与副本会带来额外的行为，本实验不涉及。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立（Elasticsearch 9.5.3），全部断言通过 | 是：版本由 9.1.5 更新为 9.5.3；路由返回条数、聚合与线程池的数字按本次证据修正；「返回桶的计数都准确」不成立，要改写 |
