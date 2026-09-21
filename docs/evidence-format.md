# 证据格式

## 一、experiment.yaml

```yaml
id: storage/order-by-limit-index-choice   # 与目录路径一致
title: 一句话说明实验内容
status: verified            # planned | verified | partial | stale | archived
tier: performance           # regular | regular-docker | performance，决定 CI 何时运行
article:
  repository: codesphere
  path: docs/storage/database-design-and-tuning.md
related_articles:           # 可选
  - docs/...
runtime:
  os: macOS 27.0
  architecture: arm64
  docker: "29.7.2"          # 用到容器时填写
  components:               # 精确版本；不确定时不填，不猜
    mysql: "8.4.11"
resources:                  # 可选：容器资源限制
  cpu_limit: "2"
  memory_limit: 2g
dataset:                    # 可选：数据量与生成方式
  rows: 3000000
  generator: schema/02-seed.sql
commands:
  verify: make verify
  evidence: make evidence
  clean: make clean
evidence:
  verification: VERIFICATION.md
  directory: evidence/
last_verified: "2026-09-22"
```

不写硬件型号、用户名、主机名和绝对路径。`make check` 会校验必填字段、`id` 与路径一致、`status` 与 `tier` 的取值。

## 二、evidence/ 目录

- `README.md`：列出每个文件的内容与生成方式，写明规范化规则。
- `environment.txt`：由 `shared/scripts/lib.sh` 的 `write_environment` 生成，包含系统、架构、CPU 数、内存、JDK、Docker 版本。
- 原始输出：`.txt` 或 `.log`；需要比较或绘图的数据同时保存 `.csv` 或 `.json`。
- 数据库实验：按 `before/`、`after/`、`control/` 分组，每条查询保存 `query.sql`、`explain-analyze.txt`、`explain.json`、计数与结果校验和，参见 [storage/order-by-limit-index-choice](../storage/order-by-limit-index-choice/evidence/README.md)。

## 三、规范化规则

采集脚本可以做确定性的规范化，但必须在 `evidence/README.md` 中说明，并保留原始语义：

| 内容 | 处理 |
|---|---|
| 测试总耗时 | 替换为 `<elapsed>` |
| 日志时间戳、线程号、容器 ID | 替换为占位符 |
| 仓库绝对路径 | 替换为 `/workspace`；编译错误中改为相对路径 |
| 用户目录 | 替换为 `~` |
| surefire XML 报告 | 不归档（含全部系统属性），只提取用例名与结果 |

不得修改性能数值、异常栈、执行计划节点和断言涉及的任何内容。

## 四、数据库实验的最低要求

- 镜像 digest、`compose.yaml`、配置文件、CPU 与内存限制；
- 表结构、索引、确定性造数脚本（固定种子或由主键计算）、数据分布；
- 预热次数、采样次数、并发度与统计口径；
- 优化前后完整的 `EXPLAIN ANALYZE` 与 `EXPLAIN FORMAT=JSON`；
- 读取行数（Handler 计数）、缓冲池请求与物理读等可获取指标；
- 各写法返回结果一致的校验。
