# 验证记录：访问令牌验签之后还要校验什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 令牌 | 默认解码器（typ=JWT） | 加签发者、受众、at+jwt 类型校验 |
|---|---|---|
| typ=at+jwt 的正确令牌 | **拒绝**：typ 必须是 JWT | 接受 |
| 签发者、受众正确 | 接受 | 接受 |
| 受众是 billing-api | **接受** | 拒绝 |
| 签发者不同（同一把密钥） | **接受** | 拒绝 |
| ID Token（受众是网页应用） | **接受** | 拒绝 |
| 30 秒前过期 | **接受**（默认时钟偏差 60 秒） | 接受 |
| 5 分钟前过期 | 拒绝 | 拒绝 |
| 没有 exp | **接受** | **接受**；再加 `setAllowEmptyExpiryClaim(false)` 才拒绝 |
| 另一把同 kid 的密钥 | 拒绝 | 拒绝 |
| alg=none | 拒绝 | 拒绝 |
| HS256，用公钥字节当密钥 | 拒绝 | 拒绝 |

scope 判断：`"orders.read orders.write"` 用 `indexOf(...) > 0` 得到 false（首项漏判）；`"profile orders.readonly"` 得到 true（子串误判）；按空格拆成集合精确匹配都正确。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 `NimbusJwtDecoder.withPublicKey` 的本地验签；从 JWK Set 地址或签发者地址创建解码器时，Spring Security 和 Spring Boot 会按配置自动加上签发者或受众校验，默认值以对应版本的文档为准。
- 没有覆盖令牌撤销：自包含令牌在过期前不会因撤销而失效，这部分在授权码流程的实验里与内省对比。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-20 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
