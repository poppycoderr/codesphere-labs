# 授权码、PKCE 与 state 各自防什么

对应文章：[oauth-code-flow-binding.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/oauth-code-flow-binding.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里运行，依赖由 `pom.xml` 固定（Spring Boot 4.1.1 的 `spring-boot-starter-security-oauth2-authorization-server`，带入 Spring Security 7.1.1 的授权服务器）。进程内启动授权服务器，只监听容器内回环地址；登记三个客户端：机密客户端 `web`（client_secret_basic，默认令牌设置）、从它复制并关闭刷新令牌复用的 `web-rotate`、公共客户端 `spa`（无密钥）。演示用户与客户端密钥都是 `example_password`，用 Spring 默认的委托编码器存储。客户端一侧用带 Cookie 的 `java.net.http.HttpClient` 手工走流程（不跟随重定向）：取登录页与 CSRF 令牌、登录、发起授权请求、从重定向里取授权码、到令牌端点换令牌，再逐项发送错误的请求，并用内省、撤销端点与基于 JWK 的本地解码器对比。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
