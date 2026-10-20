# 验证记录：授权码、PKCE 与 state 各自防什么

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 未登录时发起授权请求 | 302 到 /login |
| 机密客户端不带 code_challenge（默认设置） | 重定向回客户端，error=invalid_request（code_challenge） |
| 带 S256 挑战的正常流程 | 带回 code 与原样的 state；换到访问令牌、刷新令牌与 ID Token |
| 同一授权码再换一次 | 400 invalid_grant |
| 码被重放后，这次授权的刷新令牌 | 400 invalid_grant |
| 码被重放后，内省第一次的访问令牌 | active=false |
| redirect_uri 未登记、比登记的多一段路径 | 400，不重定向 |
| 换令牌时 redirect_uri 不同 | 400 invalid_grant |
| 授权码交给另一个客户端去换 | 400 invalid_grant |
| 公共客户端不带 code_challenge | error=invalid_request |
| 错误的 verifier / 不给 verifier | invalid_grant / invalid_client |
| 正确的 verifier（公共客户端） | 200，有访问令牌，没有刷新令牌 |
| code_challenge_method=plain | error=invalid_request |
| `TokenSettings` 默认值 | reuseRefreshTokens=true |
| 默认设置刷新 | 新旧刷新令牌相同，同一个可以反复使用 |
| 关闭复用后刷新 | 刷新令牌轮换；旧的再用 invalid_grant |
| 旧刷新令牌被重放之后，新的刷新令牌 | 仍然可用（没有做重放检测） |
| 撤销访问令牌之后 | 内省 active=false；资源服务器用 JWK 本地验签仍接受 |
| 把 ID Token 当访问令牌交给只验签的解码器 | 接受（aud=[web]） |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 Spring Security 7.1.1 授权服务器的默认行为与少数显式设置，内存存储；其他授权服务器（Keycloak、Auth0 等）的默认值不同。
- 浏览器、真实回调与 state 校验都在客户端一侧，本实验只检查授权服务器是否原样带回 state，没有实现客户端的校验。
- 授权服务器与客户端在同一进程，没有覆盖 TLS、跨域会话与多实例部署。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-21 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
