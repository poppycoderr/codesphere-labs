# Elasticsearch 翻到很深的页

对应文章：[es-deep-paging.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/es-deep-paging.md)。

`compose.yaml` 启动固定 digest 的单节点 Elasticsearch 9.5.5（关闭安全认证、不暴露端口）；`src/lab.py`（只用标准库）建 3 个主分片、12000 条文档的索引（`score` 只有 100 个值、`pid` 唯一），依次验证 `from+size` 的上限、按 `_id` 排序、`search_after` 是否有唯一的决胜字段、翻页之间修改文档的影响、PIT 的快照、PIT 的隐式 `_shard_doc` 决胜与关闭 PIT。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
