# 金额的来历：快照、价格版本与凭证链

对应文章：[data-provenance-snapshots.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/data-provenance-snapshots.md)。

`src/ProvenanceLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，连接同一 compose 项目里的 PostgreSQL 18，所有数据写在源码里。五组：订单行只引用商品、按当前价格计算金额；订单行保存成交单价；价格按（商品、价格类型、有效期）分版本，`EXCLUDE` 约束保证同类价格不重叠，订单行引用版本；报价带有效期；发票记录来源订单的版本号，以及一个禁止修改已开票订单金额的触发器。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（不含拉取镜像）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
