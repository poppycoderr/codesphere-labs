# 告警规则在数什么

对应文章：[alert-rule-semantics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/alert-rule-semantics.md)。

`compose.yaml` 启动固定 digest 的 Prometheus 3.15.1 与 Alertmanager 0.34.1（抓取与评估间隔 1 秒，规则见 `config/rules.yml`，路由见 `config/alertmanager.yml`）；`src/lab.py`（只用标准库）以网络别名 `lab` 运行，同时提供被抓取的 `app_errors_total` 与两个 webhook（`/ok` 返回 200，`/broken` 返回 500），依次制造一次 +8 的尖峰、持续 14 秒的错误、先持续后消失的序列，每 0.25 秒从 `/api/v1/alerts` 记录告警状态，最后读取 webhook 收到的通知与 Alertmanager 的 `/metrics`。

```bash
make verify     # 需要 Docker；约 2 分钟（不含拉取镜像）
make evidence   # 重新采集 evidence/
make clean
```
