# 让模型产出数据而不是代码

对应文章：[data-not-code-import.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/data-not-code-import.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，只用标准库，容器无网络。输入是一份模拟模型生成的 CSV：带 UTF-8 BOM，含一个带逗号的引号字段、一个带换行的字段、一个非法状态值、一个重复 ID、一个不存在的部门。先用常见的生成脚本导入（按行、按逗号切分，不跳表头，每行单独提交，异常吞掉），再用固定程序导入（`csv.DictReader` + `utf-8-sig`、Schema 校验、SQLite 单事务），比较报告与库中实际结果。

## 快速运行

```bash
make verify     # 需要 Docker；约 5 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
