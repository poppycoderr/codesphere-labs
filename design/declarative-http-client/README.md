# 声明式 HTTP 客户端

对应文章：[declarative-http-client.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/declarative-http-client.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里运行，依赖由 `pom.xml` 固定（Spring Framework 7.0.9），Maven 容器下载。进程内启动一个 JDK `HttpServer` 作为后端，回显方法、原始路径、解码后的路径、原始查询串和请求体，另有返回 404、500 与 3 秒后才响应的路径。客户端是 Spring HTTP Interface（`HttpServiceProxyFactory` + `RestClientAdapter`）生成的代理，依次验证：代理类型、不写参数名的 `@PathVariable`、路径变量与查询参数的编码、POST 请求体、接口默认方法、404/500 的处理、默认与设置读取超时后的等待。最后用一个约 10 行的手写 JDK 动态代理对照两种常见的编码错误。端口号在输出里替换为 `<port>`。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
