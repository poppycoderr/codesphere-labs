# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `facts.tsv` | 测试记录的事实，每行「键<TAB>事实」 |
| `test-results.txt` | 测试数量与失败数 |
| `maven-test.log` | `mvn test` 的输出（路径与时间戳已规范化） |
| `dependencies.txt` | 运行时依赖及版本 |
| `environment.txt` | 运行环境 |
