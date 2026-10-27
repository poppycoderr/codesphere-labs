# 同一个 Bean 由什么决定

对应文章：[bean-identity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/bean-identity.md)。

用 `AnnotationConfigApplicationContext` 与 `SpringApplication` 验证 Spring 容器里「同一个组件」的几种判定：配置类的代理模式、FactoryBean 的两个名字、手工注册的单例、同名 Bean 的覆盖规则。

```bash
make verify     # 需要 JDK 21+、Maven
make evidence   # 重新采集 evidence/
make clean
```
