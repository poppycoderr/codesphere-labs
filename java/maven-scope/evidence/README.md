# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `classpath-compile.txt`、`classpath-runtime.txt`、`classpath-test.txt` | 三种类路径包含的依赖 |
| `run-with-runtime-classpath.txt` | 只用运行时类路径运行 `App` 的输出 |
| `maven-compile.log`、`environment.txt` | 构建日志与运行环境 |
