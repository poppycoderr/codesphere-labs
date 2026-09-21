# 贡献指南

## 新增或修改实验

1. 先确认 codesphere 文章中哪些结论需要证据，优先扩展已有实验，避免重复工程。
2. 从 [`shared/templates/experiment/`](shared/templates/experiment/) 复制模板，填写 `experiment.yaml`。
3. 写最小代码和 `scripts/verify.sh`：一条命令完成运行与断言，失败时返回非零退出码。
4. 运行 `make verify EXP=<专题/实验>`，通过后运行 `make evidence EXP=<专题/实验>` 采集证据。**先跑实验，再写结论**，不要挑选支持预设结论的输出。
5. 写 `VERIFICATION.md`，说明结果能证明什么、不能证明什么。
6. 运行 `make check`，通过后提交。

## 脚本约定

- 使用 `set -euo pipefail`，通过 `shared/scripts/lib.sh` 中的 `require`、`expect_line`、`expect_regex` 做前置检查和断言。
- 容器使用实验专属的 compose 项目名（`csl-` 前缀），不暴露宿主机端口；`make clean` 只删除本实验创建的资源。
- 密码使用 `example_password`，路径使用相对路径，证据中不出现用户名、主机名和个人绝对路径。
- 可调参数通过环境变量传入，默认值适合本地最小复现。

## 性能数据

- 只断言相对关系、执行计划节点或数量级，不用严格的耗时阈值判断成败。
- 写清预热次数、采样次数、并发度、统计口径和资源限制。

## 提交信息

沿用 Conventional Commits，例如 `feat(storage): add order-by-limit experiment`、`fix(design): normalize timestamps in evidence`。
