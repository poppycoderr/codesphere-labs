# 验证记录：对象创建与生命周期

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 不校验、不复制的 Builder：`connectTimeout` 5 秒、`readTimeout` 1 秒的配置被成功创建；`build()` 之后继续往 Builder 加渠道，已建成的对象跟着变化。
2. 把校验交给 `record` 紧凑构造器：`build()` 抛出 `connectTimeout PT5S > readTimeout PT1S`；渠道列表被复制且不可修改。
3. 静态单例在两个类加载器中各有一个，计数分别为 3 与 1。
4. 同一容器两次 `getBean` 得到同一个 singleton，两个容器各一个；prototype 直接注入 singleton 后始终是同一个实例，`ObjectProvider.getObject()` 每次新建。
5. 浅复制后向副本加人，原报名的名单变为 `[alice, mallory]`。
6. 保留 id 的深复制被保存后，仓储中只有 1 条，原报名的名单被覆盖，再保存原对象时版本冲突；以模板新建得到新 id、version 1、0 个未发布事件。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21、spring-framework 7.0.9、junit 5.13.4。

## 三、执行步骤

`mvn test` 运行 `CreationTest` 的 6 个用例；每个用例把观察到的事实写入 `target/facts.tsv`，脚本排序后保存为 `evidence/facts.tsv`，并从 surefire 报告整理用例结果。

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/facts.tsv` | 每个用例记录的事实 |
| `evidence/test-results.txt` | 用例名与结果（从 surefire XML 整理） |
| `evidence/maven-test.log` | Maven 输出 |
| `evidence/dependencies.txt` | 解析后的依赖版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 仓储是内存实现，用来说明「按 id 保存」的语义；真实数据库的覆盖表现为 UPDATE 命中原行。
- 只覆盖 singleton 与 prototype，没有启动 Web 容器验证 request、session 作用域。
- 类加载器场景用 `URLClassLoader` 模拟应用服务器或插件隔离。

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
