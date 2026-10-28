# 实例下线后，调用方多久才不再访问它

对应文章：[registry-deregistration-visibility.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/registry-deregistration-visibility.md)。

Maven 项目（Spring Boot 4.0.9 + Spring Cloud 2025.1.4；对应 Boot 4.1 的 Spring Cloud 2026.0 尚无正式版）。`DeregistrationTest` 在同一个 JVM 里启动 Eureka Server、两个服务实例 A/B 与一个经 LoadBalancer 调用的调用方，所有间隔缩短到秒级（心跳 1s、租约 3s、剔除检查 1s、响应缓存 3s、客户端拉取 3s、LoadBalancer 缓存 5s）。调用方每 50ms 请求一次，同时每 100ms 检查 B 在注册表、注册中心接口、调用方本地副本中是否仍为 UP；分别执行正常停机、崩溃（直接停掉端口与心跳，不走 Spring 生命周期）、先注销再等 16 秒再停服务。结论写入 `facts.tsv`，各层秒数写入 `timings.txt`。

```bash
make verify     # 需要 Docker；约 2 分钟（不含首次下载依赖）
make evidence   # 重新采集 evidence/
make clean
```
