# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。各 `.txt` 文件第一行是 Maven 的退出码，其余是从构建日志里筛出的测试统计、重跑记录与构建结论（去掉了耗时）。

| 文件 | 内容 |
|---|---|
| `no-rerun.txt` | 不重跑 |
| `rerun-2.txt` | `-Dsurefire.rerunFailingTestsCount=2` |
| `rerun-2-report.txt` | 上一次运行的 Surefire XML 报告里的 `flakyFailure` 元素 |
| `rerun-2-fail-on-flake.txt` | 再加上 `-Dsurefire.failOnFlakeCount=1` |
| `order-together.txt` | `InventoryTest` 两个测试一起运行，重跑 2 次 |
| `order-alone.txt` | 只运行 `InventoryTest#b_firstDelivery` |
| `retry-odds.tsv` | 模拟结果：失败概率、重跑次数、构建失败的比例、公式值 |
| `environment.txt` | 运行环境、Maven 镜像 digest 与各插件版本 |
