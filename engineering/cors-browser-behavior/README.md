# 跨源请求在浏览器里的表现

对应文章：[cors-browser-behavior.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/cors-browser-behavior.md)。

在固定 digest 的 Playwright 镜像（自带 Chromium 153）里运行。`src/servers.py` 启动两个本地服务：8000 端口提供页面，9000 端口是接口，两者端口不同，是两个源。接口的每个路径返回不同的跨源响应头，并记录自己实际收到的请求。`src/cors.py` 用 Playwright 打开 8000 的页面，在页面里用 `fetch` 请求 9000 的接口，记录页面脚本拿到的结果，再与接口的记录对照。

场景：响应没有跨源头时的 GET 与 `text/plain` 的 POST；`application/json` 的 POST 与预检；`Access-Control-Allow-Origin: *`；读取自定义响应头；带 Cookie 的请求；预检缓存。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取约 2.4 GB 的镜像）
make evidence
make clean
```
