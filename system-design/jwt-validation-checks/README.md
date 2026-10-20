# 访问令牌验签之后还要校验什么

对应文章：[jwt-validation-checks.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/jwt-validation-checks.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里运行，依赖由 `pom.xml` 固定（`spring-security-oauth2-jose` 7.1.1，带入 Nimbus JOSE+JWT 10.9.1）。用 Nimbus 生成两把 RSA 密钥并签发一组令牌：正确的、受众不同、签发者不同、像 ID Token 的、30 秒和 5 分钟前过期、没有 exp、用另一把同 kid 的密钥签名、`alg=none`、用公钥字节当 HS256 密钥签名。每个令牌签两份（`typ=JWT` 与 `typ=at+jwt`），分别交给 `NimbusJwtDecoder` 的默认配置，以及加上 `JwtIssuerValidator`、`JwtAudienceValidator`、`JwtTypeValidator("at+jwt")` 的配置；再补一个要求 exp 的时间校验器。最后对比按 `indexOf` 与按集合判断 scope。输出里的时间替换为占位符。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
