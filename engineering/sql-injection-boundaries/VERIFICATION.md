# 验证记录：SQL 注入的边界

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `owner` 传入 `alice' OR '1'='1`，拼接 | 返回 5 行（alice 只有 2 行） |
| 同样的输入，绑定参数 | 0 行 |
| `id` 传入 `1 OR 1=1`，数值直接拼接 | 5 行 |
| `id` 传入 `0 UNION SELECT api_token FROM accounts` | 读到 `accounts` 表里的两个令牌 |
| `ORDER BY ?` 绑定 `"price"` | 不报错，返回顺序是 1、2、3、4、5（没有按价格排） |
| `ORDER BY price ?` 绑定 `"DESC"` | `SQLSyntaxErrorException` |
| 排序列拼接，传入 `CASE` 子查询，逐位猜 | 读出 admin 的 `api_token`：`tok_k7` |
| 排序参数查白名单 | 合法值正常排序，其余拒绝 |
| `LIKE CONCAT('%', ?, '%')` 绑定 `%` | 5 行全部返回 |
| 同上，绑定 `a_b` | 匹配到 `a_b tester` 与 `axb adapter` |
| 把 `\`、`%`、`_` 转义后绑定 | 只匹配 `a_b tester` |
| `IN (?)` 绑定 `"1,2,3"` | 只返回 id 1 |
| `IN (?, ?, ?)` 逐个绑定 | 返回 1、2、3 |
| 输入里带 `; UPDATE …`，默认连接参数 | `SQLSyntaxErrorException`，数据没变 |
| 同样的输入，连接串加 `allowMultiQueries=true` | alice 的角色被改成 admin |
| 用绑定参数存入带引号的值，另一处读出来拼接 | 返回 6 行（应为 1 行） |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了 MySQL 8.4.11 与 Connector/J 9.7.0。`ORDER BY ?` 绑定字符串时「按常量排序」的行为、`IN (?)` 里字符串到数值的隐式转换，都是 MySQL 的行为，其他数据库可能直接报错。
- 没有覆盖 ORM 与 SQL 映射框架。它们的字符串替换语法（例如 MyBatis 的 `${}`）等同于拼接，绑定语法（`#{}`）等同于参数；这里没有针对具体框架做实验。
- 盲注只演示了「排序结果会泄露信息」这一点，逐位猜 6 个字符；没有涉及基于时间的盲注。
- 实验对象是本地容器里的演示数据，不涉及任何外部系统。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
