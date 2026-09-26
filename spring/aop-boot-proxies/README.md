# Spring Boot 下的代理

对应文章：[aop-proxy-pitfalls.md](https://github.com/poppycoderr/codesphere/blob/master/docs/spring/aop-proxy-pitfalls.md)。与 [spring/aop-proxy-pitfalls](../aop-proxy-pitfalls/)（纯 Spring Framework）互补，本实验启动 Spring Boot 4.1.1，数据库用 H2 内存库。

| 测试 | 内容 |
|---|---|
| `DefaultProxyTest` | Boot 默认的代理类型；`@Cacheable`、`@Async` 的自调用 |
| `DefaultOrderingTest`、`CacheOuterOrderingTest`、`TxOuterOrderingTest` | 同一个方法同时有 `@Transactional` 与 `@Cacheable`：缓存命中时开不开事务；事务回滚后缓存里留下什么 |

切面顺序由 `labs.order` 配置项选择（`Ordering.java`），`order` 越小越靠外。

## 快速运行

```bash
make verify     # 需要 JDK 21 与 Maven；约 30 秒
make evidence
make clean
```
