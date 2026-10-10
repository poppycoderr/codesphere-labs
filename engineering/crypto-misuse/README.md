# 加密与口令存储的误用

对应文章：[crypto-misuse.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/crypto-misuse.md)。

`src/CryptoLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。加密部分只用 JDK 自带的 JCE；BCrypt 部分用 jBCrypt 0.4 与 Spring Security 7.1.1 的 `spring-security-crypto`。密钥、随机数、盐都是写在源码里的固定演示值，所以输出确定。

每个场景都是「调用了正规的算法，结果仍然能被攻击」：`Cipher.getInstance("AES")` 的默认模式；同一个密钥下重复使用 GCM 的随机数；CBC 只加密不认证；密文没有绑定它属于哪条记录；用不加盐的快速哈希存口令；BCrypt 的 72 字节上限；用 `java.util.Random` 生成令牌。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 20 秒
make evidence
make clean
```
