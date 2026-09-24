# 限流组件

对应文章：[designing-reusable-server-components.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/designing-reusable-server-components.md)。

单节点 Redis 8.10.1 与 Jedis 5.2.0。`src/RateLimit.java`：

- 可控时钟下固定窗口、滑动日志、令牌桶面对「第 990ms 与第 1000ms 各到达 100 个」的结果；
- 32 个线程共享本地计数器：先检查后递增与比较并交换；
- 16 个实例共享 Redis：`GET` 判断后 `INCR` 与 Lua 原子脚本；每实例平分配额在流量不均时的放行数；
- 规则热更新：非法规则被拒绝并保留旧版本；
- 脚本在运行中 `docker pause` Redis，比较放行、拒绝、本地兜底三种降级策略（命令超时 50ms，首次失败后熔断）。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、网络可访问 Maven Central（首次下载 Jedis 及其依赖）；约 1 分钟
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物与容器
```

镜像固定 digest，容器不暴露宿主机端口。
