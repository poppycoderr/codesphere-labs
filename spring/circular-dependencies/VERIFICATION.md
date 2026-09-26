# 验证记录：Spring 循环依赖

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **构造器循环无法创建**：Spring Framework 默认配置下启动失败，`BeanCurrentlyInCreationException: Requested bean is currently in creation: Is there an unresolvable circular reference…`。
2. **字段循环取决于开关**：
   - Spring Framework 默认允许，启动成功；`setAllowCircularReferences(false)` 后失败；
   - Spring Boot 4.1.1 默认禁止，启动失败并打印说明：`Relying upon circular references is discouraged and they are prohibited by default…`；设置 `spring.main.allow-circular-references=true` 后启动成功。
3. **prototype 循环**：`getBean` 时失败，`BeanCurrentlyInCreationException`。
4. **带 `@Transactional` 的循环**：启动成功，容器里的 `TxOrder` 是代理，`TxInventory` 持有的是同一个代理。事务代理由自动代理创建器生成，它能在提前暴露引用时就返回代理。
5. **带 `@Async` 的循环**：
   - 容器启动时创建：启动成功，但 `AsyncOrder` 和 `AsyncInventory` 各构造了 2 次，最终两边持有同一个代理。第一次创建 `AsyncOrder` 时，它的原始对象已经注入 `AsyncInventory`，之后又被包装成代理，容器抛出了异常；Spring Framework 7.0.9 的预实例化逻辑捕获这个异常，只留下一条 INFO 日志（`… marked for pre-instantiation (not lazy-init) but currently initialized by other thread - skipping it in mainline thread`），随后在创建 `AsyncInventory` 的过程中重新创建了 `AsyncOrder`；
   - 同样的循环改为延迟初始化，首次 `getBean(AsyncOrder)` 直接失败：`Bean with name '…AsyncOrder' has been injected into other beans […AsyncInventory] in its raw version as part of a circular reference, but has eventually been wrapped.`
6. **`@Lazy` 打破构造器循环**：一侧构造器参数加 `@Lazy` 后启动成功，注入的是代理，第一次调用时才解析到真实对象。
7. **拆出第三个服务**：`Checkout` 依赖订单与库存、两者互不依赖，在 Spring Boot 4.1.1 默认配置下直接启动成功。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)、[`evidence/dependencies.txt`](evidence/dependencies.txt)。Spring Boot 4.1.1、Spring Framework 7.0.9、JUnit 6.0.3，JDK 21。

## 三、执行步骤

`mvn test` 运行 `CircularDependencyTest` 的 3 个用例，每种情况新建容器，事实写入 `target/facts.tsv`；Spring Boot 的失败说明与 `@Async` 循环的 INFO 日志从 Maven 输出中截取。

第 5 条的构造次数是在第一次运行发现「启动成功」与常见说法不一致之后加上的：查看 7.0.9 的 `DefaultListableBeanFactory.preInstantiateSingleton` 源码，它会捕获 `BeanCurrentlyInCreationException` 并记一条 INFO 日志后继续；再用延迟初始化确认异常本身仍然存在。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/facts.tsv` | 每种循环的结果 |
| `evidence/boot-failure-analysis.txt` | Spring Boot 的失败说明 |
| `evidence/async-retry-log.txt` | `@Async` 循环启动时的 INFO 日志 |
| `evidence/test-results.txt` | 用例名与结果 |
| `evidence/maven-test.log` | Maven 输出 |
| `evidence/dependencies.txt` | 依赖版本 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 第 5 条是 Spring Framework 7.0.9 在这个配置下的行为，依赖 Bean 的注册顺序与预实例化逻辑；在旧版本或其他创建顺序下，同样的循环可能直接启动失败。它说明的是「容器可能悄悄重建 Bean」，不是「`@Async` 循环已经安全」。
- 构造了两次意味着构造器、`@PostConstruct` 里的副作用也会执行两次；实验只计数了构造器。
- 事务管理器只驱动事务同步回调，不连接数据库。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-26 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
| 2026-09-27 | 重新采集证据：日志中的本机用户名改为 `<user>`，断言与事实不变 | 否 |
