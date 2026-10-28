# 分桶库存：调整桶数是一次迁移

对应文章：[bucket-layout-migration.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/bucket-layout-migration.md)。

`compose.yaml` 启动固定 digest 的 Redis 8.10.1；`src/BucketLayout.java`（单文件，最小 RESP 客户端）依次验证：总额拆桶的余数与 0 个桶；请求不均匀时只扣自己的桶与空桶时依次试其他桶；客户端「读总数 → 写新桶」之间插入 3 笔扣减；缩容 5 → 3 后按旧布局路由的请求，分别不校验版本与校验版本；最后 8 个线程并发扣减 1000 件，同时用一个 Lua 脚本把桶数依次调整为 7、2、5、1、3。所有键带 `{sku1}` 哈希标签。

```bash
make verify     # 需要 Docker；约 30 秒（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
