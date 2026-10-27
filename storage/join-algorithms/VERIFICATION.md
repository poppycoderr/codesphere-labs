# 验证记录：JOIN 的执行方式

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 城市过滤 + 被驱动表连接列有索引 | 先读 customers，查找 200 次，约 6ms |
| 索引只在 customers.id | 改由 orders 驱动，查找 30 万次，慢 10 倍以上 |
| 两侧都没有索引 | 哈希连接，用过滤后的 customers 建表 |
| 关闭哈希连接 | 200 次全表扫描，比哈希连接慢 20 倍以上 |
| 全量连接 | 哈希连接比索引嵌套循环快 |
| join_buffer_size 128KB / 256KB / 64MB | 哈希连接写临时文件 / 写 / 不写 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 耗时只断言相对关系（倍数阈值），绝对值随机器变化；单机容器 2 CPU、1 GB 内存。
- 临时文件个数取决于构建侧的数据量与缓冲区大小，断言只检查有与无。
- 全量连接时哈希更快取决于这里每个客户约 15 单、订单表顺序扫描很快；数据分布不同时结论可能相反。

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
