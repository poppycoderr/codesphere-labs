# 浏览器的 HTTP 缓存

对应文章：[http-cache-reuse.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/http-cache-reuse.md)。

在固定 digest 的 Playwright 镜像（自带 Chromium 153）里运行。`src/origin.py` 是本地源站，每个路径返回不同的缓存相关响应头，并记录每次收到的请求和其中的 `If-None-Match`、`If-Modified-Since`。`src/cache.py` 打开源站上的一个页面，在页面里对各个路径连续 `fetch`（默认缓存模式），统计源站实际收到的次数；最后对页面做一次普通刷新，看页面引用的两个脚本是否回源。

只涉及浏览器自己的 HTTP 缓存，没有共享缓存（代理、CDN）。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取约 2.4 GB 的镜像）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
