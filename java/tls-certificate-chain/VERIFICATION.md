# 验证记录：证书链与信任锚

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

JDK 25.0.4.1，OpenSSL 3.5.5。客户端只信任根证书（除特别说明）。

| 场景 | 服务端发来 | 客户端结果 |
|---|---|---|
| 叶证书加中间证书 | 2 张 | HTTP 200 |
| 只有叶证书 | 1 张 | `SSLHandshakeException`：PKIX path building failed … unable to find valid certification path to requested target |
| 只有叶证书，信任库里另有中间证书 | 1 张 | HTTP 200 |
| 完整的链，客户端信任另一个根 | 2 张 | 与「只有叶证书」完全相同的异常信息 |
| 完整的链，域名是 other.example | 2 张 | `SSLHandshakeException`：No subject alternative DNS name matching localhost found. |
| 完整的链，叶证书已过期 | 2 张 | `SSLHandshakeException`：PKIX path validation failed … validity check failed（根因 `CertificateExpiredException`） |

AIA 场景（只有叶证书，叶证书带中间证书的下载地址）：默认配置失败，中间证书被下载 0 次；只加 `enableAIAcaIssuers=true` 仍然失败，下载 0 次；再加 `allowedAIALocations=http://localhost:18080/` 后 HTTP 200，下载 1 次。镜像自带的 `java.security` 里 `com.sun.security.allowedAIALocations=` 为空，注释说明默认是全部拒绝。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 异常信息对应这个 JDK 构建；不同版本的措辞可能不同。
- 证书是自签的三级结构，没有吊销检查（CRL、OCSP）、证书透明度、名称约束与交叉签名。
- 没有运行浏览器。浏览器会不会自行补全中间证书（下载或使用缓存）取决于浏览器及其版本，文章里只引用各自的文档，不作为本实验的结论。
- `allowedAIALocations` 的默认值来自这个镜像的 `java.security`；它是在哪个版本引入的没有逐版本验证。
- 没有覆盖双向 TLS、证书固定与 TLS 1.3 之外的协议版本协商。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-09 | 首次建立，三份输出与预期逐行一致 | 新文章，结论取自本次证据 |
