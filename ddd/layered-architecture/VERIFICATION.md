# 验证记录：分层、应用服务与仓储

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 主代码 30 个类满足三条规则：四层依赖方向（适配层只能访问应用层，领域层只能被应用层和基础设施层访问）、领域层不依赖 Spring/JPA/Jackson/MyBatis、领域层不依赖外层。
2. 三个违规夹具都被拦下：
   - 领域对象持有持久化对象 `SessionPO`：被分层规则与「领域不依赖外层」同时拦下；
   - 领域对象标注 `@Component`：被「领域无框架」拦下；
   - 控制器跳过应用层直接调用仓储：被分层规则拦下。
3. 成功路径的顺序：适配器收到请求 → BEGIN → 应用服务 → 加载聚合 → `Session.register` → 带版本号的 UPDATE → COMMIT → 发布事件 → 返回 201。事件在 COMMIT 之后发布。
4. 三种失败：
   - 非法手机号：应用服务转换命令时拒绝，返回 400，没有开启事务；
   - 重复报名：领域抛出 `RegistrationRefused`，事务回滚，返回 422；
   - 加载后另一个请求先提交：保存时版本不匹配，回滚，返回 409，没有发布事件。
5. 往返检查：完整转换器 200 个样本全部一致；漏掉候补表的转换器，不一致的数量正好等于有候补的场次数。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)、[`evidence/dependencies.txt`](evidence/dependencies.txt)。

## 三、执行步骤

`mvn test` 运行 3 个测试类、7 个用例，事实写入 `target/facts.tsv`。违规夹具放在测试源码的 `labs.ddd.violations.*` 包下，由 `ArchitectureTest` 单独导入；主代码的检查排除测试类与 jar。

写这组代码时，第一版控制器直接构造了领域的 `Phone`、`SessionId` 并捕获领域异常，被分层规则拦下；最终版把命令字段改为原始值，由应用服务转换，并用 `UseCaseFailure` 把领域异常翻译给适配器。这个过程没有保留为证据。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/facts.tsv` | 规则检查、调用顺序、失败路径与往返检查的结果 |
| `evidence/test-results.txt` | 用例名与结果 |
| `evidence/maven-test.log` | Maven 输出 |
| `evidence/dependencies.txt` | 解析后的依赖版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 规则按包名识别层，与 DDK ArchGuard 的做法相同；包结构不同的项目需要改规则。
- 「适配层不能访问领域层」是本实验和 DDK 四层骨架的约定，不是唯一正确的分层方式。允许适配器使用领域值对象的项目，只需要改一条 `mayOnlyBeAccessedByLayers`。
- 内存表只模拟事务的提交与回滚，没有并发；真实数据库上的版本冲突见聚合实验。
- 往返检查只能发现「转过去再转回来不一致」的字段，发现不了两边都漏掉的字段。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
