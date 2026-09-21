# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `test-results.txt` | 12 个 JUnit 用例的结果，从 surefire XML 提取 |
| `maven-test.log` | `mvn test` 的输出，包括测试中打印的事务边界 |
| `application.log` | 演示程序 `Demo` 的输出 |
| `dependencies.txt` | `mvn dependency:list` 解析出的运行时依赖 |
| `environment.txt` | 操作系统、CPU 数、JDK、Maven 版本 |

规范化与裁剪：

- surefire 的 XML 报告包含完整的系统属性（用户目录、类路径等），没有归档；`test-results.txt` 只保留用例名与结果。
- `application.log` 中 JUL 日志行首的时间戳替换为 `<timestamp>`。
- `maven-test.log` 中的仓库绝对路径替换为 `/workspace`。
