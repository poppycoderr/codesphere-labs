# 幂等键

对应文章：[designing-reusable-server-components.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/designing-reusable-server-components.md)。

基于 MySQL 8.4.11 的幂等键（共享的 `shared/docker/mysql84`）。`src/Idempotency.java` 实现的协议：

1. `INSERT` 一行 `PROCESSING` 取得执行权（token 1、带租约）；
2. 键已存在时：请求摘要不同 → 422；`SUCCEEDED` / `FAILED` → 重放保存的响应；`PROCESSING` 且租约未过期 → 409；租约已过期 → 用 token 比较并交换接管（token 加一）；
3. 业务写入与标记成功有两种提交方式：分开提交；或在同一事务中写入业务并 `UPDATE … WHERE token = ?`，更新 0 行时回滚。

业务表 `registrations` 故意不加唯一约束，用来观察重复执行。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）；约 1 分钟
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物与容器
```

镜像固定 digest，容器不暴露宿主机端口。
