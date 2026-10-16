# 验证记录：组织结构的知识层与实例层

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| TEAM 挂在 DEPARTMENT 下 / DEPARTMENT 挂在 TEAM 下 | 成功 / 被知识层拒绝 |
| TEAM 直接挂在 DIVISION 下；在 `allowed_parent` 加一行后再挂 | 被拒绝；成功（没有改代码） |
| 删掉这条规则 | 存量里按当前规则不合法的关系 1 条 |
| TEAM-X 在职能线上挂到 DEPT-B | 成功；上级为 FUNCTION=DEPT-B, LINE=DEPT-A |
| 不结束旧关系直接新增调动后的上级 | 违反排他约束 |
| 旧关系截止到 7 月 1 日再新增 | 成功；6 月 15 日的上级 DEPT-A，8 月 1 日 DEPT-C |
| 顺序加 D1 → D2、D2 → D1 | 第二条被拒绝：会形成环 |
| 两个事务并发加这两条边，READ COMMITTED | 都提交，2 个组织处在环里 |
| SERIALIZABLE | 1 个成功，另一个 could not serialize access due to read/write dependencies among transactions |
| 先 `FOR UPDATE` 锁住结构线 | 1 个成功，另一个检查到会形成环 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 成环检查没有考虑有效期：所有参与检查的关系都从同一天开始、没有结束日期。按时间判断无环时，要在关系的有效期相交的范围内检查。
- SERIALIZABLE 下失败的事务需要重试，实验没有实现重试。
- 演示用 SQL 拼接了固定的常量；真实代码应使用参数化查询。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
