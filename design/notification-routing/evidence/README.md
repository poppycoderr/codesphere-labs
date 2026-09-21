# 证据说明

| 文件 | 内容 | 生成方式 |
|---|---|---|
| `test-output.txt` | JUnit 10 个测试的树形报告 | `scripts/verify.sh evidence` |
| `shadow-output.txt` | 三轮影子比对的一致条数与差异分类 | 同上，`java Shadow` 的完整输出 |
| `dsl-output.txt` | 内部 DSL 构造的路由对 P1、P3 告警的输出 | 同上，`java Dsl` |
| `router-dependencies.txt` | `Router` 字节码中出现的外围类型名，期望为空 | 同上，`javap -c -p Router` 后过滤 |
| `environment.txt` | 操作系统、CPU 数、JDK、JUnit 版本 | `write_environment` |
| `commands.sh` | 重新生成本目录的命令 | 手写 |

规范化规则：

- `test-output.txt` 中的总耗时替换为 `<elapsed>`，其余内容未改动。原始耗时在文章中记录为 62ms，与机器有关，不作为结论。
- 其余文件是程序的原始输出。
