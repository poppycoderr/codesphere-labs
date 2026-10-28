# 多实例定时任务只跑一次

对应文章：[scheduled-job-once.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/scheduled-job-once.md)。

`compose.yaml` 启动固定 digest 的 MySQL 8.4.11；`src/JobOnce.java`（单文件 + JDBC）里两个调度实例是同一进程中的两个对象，各自独立连接数据库，交错与停顿由代码固定：两个实例都执行；用 `job_lease` 表条件更新抢租约（时间用数据库时钟，令牌每次加一）；A 停顿 800ms 使 500ms 租约过期、B 接管后两者先后写结算结果，分别不设防与带令牌校验 + 运行键；同一周期完成后再写一次；最后用 `cancel(true)` 取消一个忙循环任务与一个阻塞在 `SELECT SLEEP(1)` 的任务。

```bash
make verify     # 需要 Docker；约 1 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
