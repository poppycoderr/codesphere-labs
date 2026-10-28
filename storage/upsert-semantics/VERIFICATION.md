# 验证记录：冲突时到底做了什么：INSERT IGNORE、ON DUPLICATE KEY UPDATE 与 REPLACE

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| INSERT IGNORE | 重复 0 行/1062；超长截断 1265；NULL 写成空串 1048 |
| ON DUPLICATE KEY UPDATE 影响行数 | 新插入 1，改了值 2，值没变 0 |
| 自增值 | 5 次走更新分支后，新行 id 12，最大 id 6 |
| 两个唯一键与两行冲突 | 只更新一行 |
| VALUES(col) | 警告 1287 |
| REPLACE | 影响 2 行，id 变了，触发器与级联生效；与两行冲突时影响 3 行 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 影响行数是 mysql 客户端的默认语义；JDBC 驱动等客户端可能默认返回「匹配行数」，值没变时得到的是 1，本实验没有覆盖。
- 多个唯一键同时冲突时更新哪一行，文档不作保证；本实验只说明这里更新了 alice。
- sql_mode 为 MySQL 8.4 默认值（含 STRICT_TRANS_TABLES）。

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
