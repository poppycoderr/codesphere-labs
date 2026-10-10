# 验证记录：跨源请求在浏览器里的表现

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 页面脚本 | 接口实际收到 |
|---|---|---|
| 不经过浏览器直接请求（没有跨源头） | 正常读到 | — |
| 页面里 `fetch`，响应没有跨源头 | `TypeError`，读不到 | GET 1 次 |
| `text/plain` 的 POST，没有跨源头 | `TypeError`，读不到 | POST 1 次，业务操作执行了 1 次 |
| `application/json` 的 POST，接口不处理 OPTIONS | `TypeError` | OPTIONS 1 次，POST 0 次 |
| 接口正确应答预检并带跨源头 | 读到响应 | OPTIONS 1 次，POST 1 次 |
| `Allow-Origin: *` | 读到响应，`X-Request-Id` 为 null | |
| 另带 `Expose-Headers: X-Request-Id` | `X-Request-Id` = req-42 | |
| 带 Cookie，`Allow-Origin: *` | `TypeError` | 收到 |
| 带 Cookie，写明了源但没有 `Allow-Credentials` | `TypeError` | 收到 |
| 带 Cookie，写明了源并带 `Allow-Credentials: true` | 读到响应 | 收到 |
| 预检带 `Max-Age: 600`，连续两次 POST | | OPTIONS 1 次，POST 2 次 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了 Chromium 153（Playwright 1.63.0 自带的版本），无头模式。其他浏览器的行为以各自的实现为准，预检缓存的上限各家不同。
- 页面与接口都在 `localhost` 的不同端口上：不同源，但属于同一个站点，所以 Cookie 的 SameSite 限制在这里不起作用；跨站点时 Cookie 是否发送还受 SameSite 控制，实验没有覆盖。
- 没有覆盖 HTTPS、重定向、专用网络访问的限制。

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
