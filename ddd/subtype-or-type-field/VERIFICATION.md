# 验证记录：子类型还是类型字段

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 新增类型 LEAVE，不改两段带 default 的分支 | 时薪 0（按内部项目），可计费 true（按客户项目） |
| sealed 接口，switch 表达式没有 default，新增 `LeaveActivity` | 编译失败：the switch expression does not cover all possible input values |
| 休假继承项目、重写 `charge` 抛异常，对所有目标统一 `charge(2)` | 第 2 个抛出 UnsupportedOperationException，第 1 个已计费 2 小时 |
| 租户把每日上限从 8 改为 7.5 | 复制的项目仍是 8；动态继承的变成 7.5；覆盖为 10 的仍是 10 |
| 租户关闭「允许覆盖」 | 项目 B 存着 10，生效值 7.5 |
| 每个具体类型一张表、各自 SERIAL | 两个目标的 id 都是 1 |
| 工时用 (target_type, target_id) 引用 | 引用不存在的目标成功；删除被引用的项目成功；2 条工时悬空 |
| 共同父表分配 id，子表 (id, kind) 外键引用父表 | 悬空引用、删除被引用目标、把 LEAVE 登记成客户项目都被外键拒绝 |
| 只插父表一行、不插子表 | 成功：「恰好有一个子类行」外键表达不了 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 编译检查只演示 switch 表达式的穷尽性；带 default 的 switch、if-else 链和 `instanceof` 判断不会得到这个检查。
- 「恰好有一个子类行」可以用延迟约束触发器或在应用层的仓储里保证，实验没有实现。
- 默认值与覆盖值只演示两种继承语义的差别，没有涉及规则版本的存储方式。

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
