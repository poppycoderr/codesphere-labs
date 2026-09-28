# 验证记录：GitHub SSH 与多账号

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 注释为 `macbook-2026` 时，Ed25519 公钥 94 字节，RSA 4096 公钥 738 字节；私钥权限 600。
2. `Host *` 写在前面、再给 `github-work` 配一个密钥时，`ssh -G github-work` 得到两条 `identityfile`，个人密钥排在前面：`IdentityFile` 会累加。
3. 每个别名只写一个密钥并加 `IdentitiesOnly yes` 时，`ssh -G github-work` 只剩 `identityfile ~/.ssh/id_ed25519_work`，`hostname` 为 `github.com`。
4. `includeIf "gitdir:~/code/work/"` 加 `insteadOf`：
   - `~/code/work/order-service`：`user.email=me@work.example`，远程地址被改写为 `git@github-work:acme/order-service.git`；
   - `~/code/personal/blog`：`user.email=me@personal.example`，远程地址不变；
   - 在 `~/code/work/` 下 `git clone git@github.com:acme/payment.git`，ssh 连接的是 `git@github-work`；
   - 在 `~/code/work/` 下、还不是仓库的目录里，读到的仍是个人邮箱。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)：OpenSSH 10.3p1（LibreSSL 3.3.6）、Git 2.55.0。

## 三、执行步骤

见 `scripts/verify.sh`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 公钥大小随注释长度变化，这里只说明两种算法的量级差距。
- 没有连接 GitHub，不验证认证结果本身；`ssh -G` 只说明客户端会按什么顺序、用哪些密钥去尝试。
- `ssh-add --apple-use-keychain` 与 `UseKeychain` 是 macOS 自带 OpenSSH 的扩展，本实验不操作钥匙串。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 是：公钥大小由 97、745 字节改为 94、738 字节（注释 `macbook-2026`）；其余结论一致 |
