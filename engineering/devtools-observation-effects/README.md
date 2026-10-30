# 调试工具看到的不一定是当时的状态

对应文章：[devtools-observation-effects.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/devtools-observation-effects.md)。

`src/server.py` 在容器内的回环地址上提供 `src/page.html`、`src/app.js` 和一个会计数的 POST 接口；`src/lab.py` 用 Playwright 打开页面，通过 Chrome DevTools 协议直接操作调试器：读取控制台事件里的对象预览与当前属性、设置带副作用的条件断点与普通断点（暂停 1 秒后继续）、用 `Network.replayXHR` 重放下单请求、取精确覆盖率。全部在自己的本地页面上进行。

```bash
make verify     # 需要 Docker；约 30 秒（首次安装 Playwright 约 1 分钟）
make evidence   # 重新采集 evidence/
make clean
```
