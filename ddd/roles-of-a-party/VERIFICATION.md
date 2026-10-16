# 验证记录：同一个人的几种角色

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 大实体撤销讲师（`instructor = false`） | 简介与课时费仍在 |
| 只是学员的用户 | 讲师字段 2 个为 null；「讲师必须有课时费」只能写成带标志的 if |
| 撤销后查询「9 月份是不是讲师」 | 只有当前布尔值，历史丢失 |
| 只该使用 `Learner` 的代码对参数做 `instanceof Person` 转换 | 调用 `grantAdmin()` 成功，admin=true |
| `Learner` 与 `Instructor` 视图 | 同一个对象 |
| 角色对象：两段讲师经历（3 月—10 月、2027 年起） | 9 月 15 日是讲师，11 月 15 日不是；课时费 500、600 各自保留 |
| 创建课时费为 0 的讲师角色 | 被讲师角色的构造器拒绝 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 没有涉及持久化；角色对象的存储（每种角色一张表，或角色表加类型）与[子类型](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/subtype-or-type-field.md)的取舍相同。
- 「接口不是权限边界」只演示了同一进程内的强制转换；模块系统（JPMS 不导出实现包）或独立的对象可以真正隔离能力，实验没有覆盖。

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
