# 验证记录：Postman 集合的 token 脚本

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 退出码 | token 请求 | 业务请求 | 断言失败 | 脚本失败 |
|---|---:|---|---|---:|---:|
| 常见写法，正常 | 0 | 4 次，全部成功 | 8 次，全部 200 | 0 | 0 |
| 常见写法，密钥错误 | 0 | 8 次，全部 401 | 8 次，全部 401 | 0 | 0 |
| 改进写法，正常 | 0 | 4 次，全部成功 | 8 次，全部 200 | 0 | 0 |
| 改进写法，密钥错误 | 1 | 8 次，全部 401 | **0 次** | 8 | 0 |
| 用 `pm.collectionVariables.get` 读密钥，`--env-var` 传错误密钥 | 0 | 4 次，全部成功 | 8 次，全部 200 | 0 | 0 |
| 顶层 `await pm.sendRequest` | 1 | 0 次 | 8 次，全部 401，`Authorization` 是未替换的 `{{access_token}}` | 0 | 8 |
| 回调里直接 `throw`，密钥错误 | 0 | 8 次，全部 401 | 8 次，全部 401 | 0 | 0 |

- 常见写法的刷新分支走不到：服务不返回 `refresh_token`。密钥错误时只在 Console 打一行日志，业务请求照样发出，报错落在业务接口上。
- 改进写法在密钥错误时跳过了全部业务请求，每个请求记一条「获取 token」失败断言，退出码 1。
- `pm.collectionVariables.get` 只读集合这一层，命令行 `--env-var` 的覆盖对它不生效。
- 顶层 `await` 在 Newman 6.2.2 里报 `SyntaxError: await is only valid in async functions and the top level bodies of modules`，脚本失败后请求照样发出，带着未替换的变量。
- 回调里的 `throw` 不计入任何失败，退出码 0。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/versions.txt`](evidence/versions.txt)：Node 24.21.0、Newman 6.2.2。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/run.js`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- token 申请次数取决于请求间隔与过期时间（3 秒有效、提前 1 秒刷新、间隔 800 ms），断言只要求正常路径至少申请 2 次、业务请求全部 200。
- 只验证了 Newman；Postman 桌面端对顶层 `await` 的支持与 Newman 不同，本实验不覆盖桌面端。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 否：结论与文章一致；可补充「顶层 await 时 prerequest-scripts 失败 8、退出码 1」 |
