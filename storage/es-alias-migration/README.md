# Elasticsearch 别名切换与在线迁移

对应文章：[es-alias-migration.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/es-alias-migration.md)。

`compose.yaml` 启动固定 digest 的单节点 Elasticsearch 9.5.5；`src/lab.py`（只用标准库）建 `orders_v1`（别名 `orders`，写索引）与改了映射的 `orders_v2`，用 `_reindex` 复制后，在 v1 上按固定顺序执行新增、更新、删除，分别验证直接切别名、按源系统版本号（`version_type=external`）追平、不带版本号追平、别名同时指向两个索引、原子切换与回切。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
