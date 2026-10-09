# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 每个应用：构建结果、类路径上的 `util` 版本、运行输出 |
| `tree-nearest.txt` | `nearest` 应用的 `dependency:tree -Dverbose` 输出 |
| `enforce-dependencyConvergence.txt`、`enforce-requireUpperBoundDeps.txt` | 打开对应规则后的构建结果与规则报错的首行 |
| `environment.txt` | 运行环境与镜像 digest |
