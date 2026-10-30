# 验证记录：值进库之前经过的转换

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| utf8mb3 写入表情 | ERROR 1366 |
| 客户端声明 latin1、发送 UTF-8 | 存为 6 个字符；latin1 读回正常，utf8mb4 读回乱码，按值查不到 |
| 默认排序规则 | utf8mb4_0900_ai_ci：大小写、重音不敏感，NO PAD |
| 唯一主键 José 后插 jose | ERROR 1062；utf8mb4_bin 下三行共存 |
| 'a' 与 'a ' | 0900_ai_ci 不等，general_ci 相等 |
| DECIMAL(10,2) 写 1.005 / 123456789.12 | 1.01 加 Note 1265 / ERROR 1264 |
| DECIMAL(66,2) | ERROR 1426，上限 65 |
| 0.1 + 0.2 = 0.3 | DECIMAL 成立，DOUBLE 不成立 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 客户端字符集用 mysql 命令行的 --default-character-set 模拟；JDBC 等驱动的连接参数（characterEncoding、connectionCollation）没有测。
- 只覆盖 MySQL 8.4 的默认 sql_mode（含 STRICT_TRANS_TABLES）；非严格模式下越界值会被截断并只给警告。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-30 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
