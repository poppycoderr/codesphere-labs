# 验证记录：同一个 Bean 由什么决定

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| full 模式 @Configuration，@Bean 方法互相调用 | 都是容器里的同一个 Clock，构造 1 次 |
| proxyBeanMethods = false | 都不是容器里那个，构造 3 次 |
| @Component 里的 @Bean 方法 | 不是同一个，构造 2 次 |
| FactoryBean：getBean("x") 与 getBean("&x") | 分别是产品与工厂；isSingleton=false 时每次新产品 |
| 容器启动时对 FactoryBean 的调用 | 只有构造与 getObjectType；getObject 在取产品时才调用 |
| registerSingleton 交入的对象 | 不初始化、不销毁 |
| 两个配置类定义同名 Bean | Framework 容器后者覆盖；Spring Boot 启动失败 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。

## 三、执行步骤

`mvn test` 运行 `src/test` 下的 JUnit 测试，测试把观察到的事实写入 `target/facts.tsv`；`scripts/verify.sh` 逐行断言。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 启动阶段 getObjectType 被调用的次数是 7.0.10 的实现细节，文章只引用「启动时先调用类型预测、不调用 getObject」。
- Spring Boot 通过 spring.main.allow-bean-definition-overriding 控制覆盖，默认 false；本实验使用默认值。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
