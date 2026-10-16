# 事务脚本还是领域模型：三次需求变化的代码演进

对应文章：[script-or-domain-model.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/script-or-domain-model.md)。

`code/` 下是同一个报名需求的两种实现，每种四个版本：v0 名额与重复报名；v1 满员进候补、取消后递补；v2 调换场次；v3 场次可以为 VIP 保留席位。`code/script/` 是事务脚本（一个服务类，每个用例一个方法，直接操作内存里的「表」）；`code/model/` 是领域模型（场次聚合 `Session` 守规则，`Service` 只做调用）。两种实现都实现 `code/api/Api.java`。`src/EvolutionLab.java` 对每个版本：编译、跑同一组验收场景（每个场景标明适用的版本范围）、用行级最长公共子序列统计相对上一版改动的文件数与增删行数、统计「名额条件」（形如 `>= capacity` / `< capacity` 的行）出现的位置数。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
