# 验证记录：关注点分离前后的测试对照

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 拆分前只能写端到端测试（假价格服务 + 真实 MySQL），同一个测试白天通过、晚间优惠时段失败：expected 144.00 but was 134.00
2. 在拆分前的代码里注入「22:00 整仍然优惠」的边界 bug，端到端测试照样通过
3. 拆分后 8 个测试不依赖网络和数据库，全部通过
4. 同样的 bug 注入拆分后的代码，恰好 1 个测试失败：`二十二点整不再优惠`

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21.0.5、junit_platform 1.13.4、mysql 8.4.11、mysql_connector_java 8.0.27。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 编译 `src/correct` 与 `src/injected-bug` 两个版本
2. 启动 MySQL（`shared/docker/mysql84`，项目名 `csl-separation`）
3. 用 `-Duser.timezone` 把本地时间分别固定到 10 点和 21 点，在共享 MySQL 网络的 JDK 容器中运行 `BeforeTest`
4. 在宿主机运行拆分后的 `PricingPolicyTest` 与 `OrderCreationTest`

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/before-correct-day.txt` | 拆分前，白天 |
| `evidence/before-correct-evening.txt` | 拆分前，晚间 |
| `evidence/before-bug-day.txt` | 拆分前，注入 bug |
| `evidence/after-correct.txt` | 拆分后，正确版本 |
| `evidence/after-bug.txt` | 拆分后，注入 bug |
| `evidence/time-setup.txt` | 时区设置方式 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 文章中拆分前的测试在本地时间 20:51（Asia/Dhaka）运行失败，这里改用按当前 UTC 时间换算的 `Etc/GMT` 时区，让任何时刻都能复现两种结果。若恰好在整点前几秒运行，本地时间可能跨过 22:00，需要重跑。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在会话临时目录中实测，数字见文章 | — |
| 2026-09-22 | 迁入本仓库，全部断言通过 | 见上文「误差、限制」中与文章不同的数字 |
