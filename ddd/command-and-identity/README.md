# 请求、可信身份与命令

对应文章：[command-and-identity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/command-and-identity.md)。

`src/CommandLab.java` 用 Spring MVC 7.0.9 的 `MockMvc` 在进程内执行完整的请求绑定（不经过网络），依赖由 `pom.xml` 经固定 digest 的 Maven 3.10.0 容器下载，程序在 temurin 25 容器里以源码方式运行。认证过滤器的结果用请求属性代替：当前租户 `t-alice`、当前用户 `alice`。

比较三组写法：请求体直接绑定到领域对象，与绑定到只含客户端可决定字段的命令对象、身份取自请求上下文；按 id 查找，与按（租户，id）查找；表单直接绑定到有 `setRole` 的对象，与在 `@InitBinder` 里限定允许的字段。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 1 分钟
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
