# Redis Stream 消费组：投递、处理、确认

对应文章：[redis-stream-consumer-groups.md](https://github.com/poppycoderr/codesphere/blob/master/docs/cache/redis-stream-consumer-groups.md)。

`compose.yaml` 启动固定 digest 的 Redis 8.10.1；`src/lab.py` 用一个消费组和两个消费者 c1、c2 依次验证：读到但不确认、持有者消失、用 '0' 读回自己的待确认、XAUTOCLAIM 接管与投递次数、接管后原持有者的 XACK、毒消息与死信流、近似与精确裁剪对待确认列表的影响、XACK 是否删除消息。

```bash
make verify     # 需要 Docker；约 15 秒（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
