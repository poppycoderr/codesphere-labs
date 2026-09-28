# Postman 集合的 token 脚本（Newman）

对应文章：[postman-auth-automation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/postman-auth-automation.md)。

`src/run.js` 在固定 digest 的 `node:24-slim` 容器里运行（依赖由 `package-lock.json` 锁定，Newman 6.2.2）：

- 进程内启动一个模拟服务：`POST /token` 校验 `client_secret`（演示值 `example_password`），返回 `expires_in: 3`，按 RFC 6749 第 4.4.3 节不返回 `refresh_token`；`GET /orders` 校验 Bearer token 是否存在且未过期；服务记录每个请求的结果和 `Authorization` 头；
- 生成 7 个集合（各 8 个 `GET /orders`），用 Newman CLI 以 800 ms 间隔运行，记录退出码与 JSON 报告：常见写法与改进写法各跑正常、密钥错误两次；再跑三个坑：用 `pm.collectionVariables.get` 读密钥时传入 `--env-var`、顶层 `await pm.sendRequest`、在回调里直接 `throw`。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（首次需要下载依赖）
make evidence
make clean
```
