# 证书链与信任锚

对应文章：[tls-certificate-chain.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/tls-certificate-chain.md)。

在固定 digest 的 temurin 25 容器里，`scripts/pki.sh` 用 openssl 现场生成根、中间、叶三级证书和几张用于对照的叶证书；`src/ChainLab.java` 在本机启动 HTTPS 服务，换用不同的密钥库，再用只信任根证书的 JDK `HttpClient` 访问：

1. 密钥库里是叶证书加中间证书；
2. 密钥库里只有叶证书；
3. 只有叶证书，但客户端的信任库里另有中间证书；
4. 完整的链，客户端信任的是另一个根；
5. 完整的链，证书里的域名不是 `localhost`；
6. 完整的链，叶证书已过期；
7. 只有叶证书，叶证书的 AIA 扩展指向中间证书的下载地址：默认配置、只加 `-Dcom.sun.security.enableAIAcaIssuers=true`、再加 `-Dcom.sun.security.allowedAIALocations=...` 三种运行方式。

每个场景另用一个不做校验的连接记录服务端实际发来了几张证书。证书与密钥随容器销毁，口令是演示值。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒
make evidence
make clean
```
