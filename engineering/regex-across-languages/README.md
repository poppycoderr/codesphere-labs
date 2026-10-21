# 同一个正则在 Java、Python、JavaScript 里

对应文章：[regex-across-languages.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/regex-across-languages.md)。

`src/Regex.java`、`src/regex.py`、`src/regex.js` 是同一组用例，分别在固定 digest 的 temurin 25、python:3.14.8-slim、node:24.21.0-slim 容器里运行，输出按「用例名、语言」排序后逐行比较。

## 快速运行

```bash
make verify     # 需要 Docker；约 15 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
