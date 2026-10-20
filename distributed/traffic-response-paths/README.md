# 分流位置、地址可见性与非幂等重试

对应文章：[docs/distributed/traffic-response-paths.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/traffic-response-paths.md)。

urllib 对 302 POST 改为 GET 并丢掉请求体；不自动跟随 307 POST。HTTP 和 TCP 全代理的 socket 对端都是代理，HTTP 入口明确写入 X-Forwarded-For。Nginx 的 http_503 配置下，只有显式允许 non_idempotent 才将已发出的 POST 重试到备用端。权威答案变化后，5 秒 TTL 缓存仍短暂返回旧地址，再过期更新。

## 运行

```bash
make verify
make evidence
make clean
```

需要 Docker；首次拉取和依赖下载耗时不固定。脚本只使用本实验的文件和容器，不修改宿主机磁盘或外部服务。环境、证据与限制见 [VERIFICATION.md](VERIFICATION.md)。
