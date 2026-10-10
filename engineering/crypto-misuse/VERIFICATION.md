# 验证记录：加密与口令存储的误用

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `Cipher.getInstance("AES")` 加密三段相同的 16 字节 | 三个密文块完全相同（ECB） |
| 同样的明文用 `AES/GCM/NoPadding` | 密文块不同 |
| 两条消息用同一个密钥和同一个 GCM 随机数 | 两段密文异或等于两段明文异或；已知第一条明文即可算出第二条 |
| 同一个 `Cipher` 对象不重新 `init` 就加密第二条 | `IllegalStateException` |
| 每次新建 `Cipher`、传入同样的随机数 | 没有任何报错 |
| AES/CBC，把 IV 的一个字节异或一下 | 解密得到 `amount=900&to=bob&ref=20261010`（原文是 100），不报错 |
| AES/GCM，同样改一个字节 | `AEADBadTagException` |
| 把一行的 GCM 密文复制到另一行解密 | 正常解出 |
| 加密时把所属用户作为附加认证数据，换一个用户解密 | `AEADBadTagException` |
| SHA-256 不加盐，两个用户口令相同 | 存下来的值相同 |
| PBKDF2-HMAC-SHA256、60 万次迭代、每人一个盐 | 值不同；一次 298 ms |
| jBCrypt 0.4：存入 72 个 a 加后缀，用 72 个 a 加另一个后缀校验 | 通过 |
| 24 个汉字（72 字节）加不同的后缀 | 通过 |
| Spring Security 7.1.1 `BCryptPasswordEncoder.encode` 传入 86 字节 | `IllegalArgumentException: password cannot be more than 72 bytes` |
| 同一个编码器，`matches` 校验旧哈希，输入 72 个 a 加错误的后缀 | `matches = true` |
| 看到 `java.util.Random` 连续两个 `nextInt()` 的输出 | 最多试 65536 次算出内部状态，预测的第三个值与实际相同 |

单线程 SHA-256 约每秒 443 万次（这台机器、这次运行）。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 演示的是算法的使用方式，不是对任何真实系统的攻击。密钥、随机数都是固定的演示值。
- 哈希速度是单线程、JDK 自带实现的结果；专用硬件上的差距更大，实验没有测量。
- 没有覆盖 Argon2、scrypt，没有覆盖时序侧信道、密钥管理与轮换。
- BCrypt 的行为只验证了 jBCrypt 0.4 与 Spring Security 7.1.1 两个实现；其他实现对超长输入的处理可能不同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-10 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
