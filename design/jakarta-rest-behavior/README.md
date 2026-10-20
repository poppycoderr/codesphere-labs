# Jakarta REST 的默认行为

对应文章：[jakarta-rest-behavior.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/jakarta-rest-behavior.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里，用 Jersey 4.0.3（Jakarta REST 4.0 实现，依赖由 `pom.xml` 固定、Maven 容器下载）在进程内启动 JDK `HttpServer`，只监听容器内回环地址，再用 `java.net.http.HttpClient` 发请求、记录原始状态码、`Allow`、自定义头、`Content-Length` 与响应体。资源覆盖：返回 String、null、void、显式 `Response`；对只有 `@GET` 的方法发 HEAD 并统计 GET 方法被调用的次数；默认 OPTIONS 与 405；`WebApplicationException`、有映射器的异常、映射器自身抛异常、未映射异常、没有 `MessageBodyWriter` 的返回类型；查询、路径、请求头参数的缺省与转换失败；同时有 String 构造器、`valueOf`、`fromString` 的类型与枚举；转换构造器抛异常；`List<Integer>` 参数。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
