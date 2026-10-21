# 反斜杠的三层转义

对应文章：[regex-escape-layers.md](https://github.com/poppycoderr/codesphere/blob/master/docs/tools/regex-escape-layers.md)。

`src/Escape.java`、`src/escape.py`、`src/escape.js` 是同一组用例，分别在固定 digest 的 temurin 25、python:3.14.8-slim、node:24.21.0-slim 容器里运行，输出按「用例名、语言」排序后逐行比较。用例围绕三层转义：源码里的字符串字面量、正则表达式、替换模板；以及 `Pattern.quote` / `re.escape` / `RegExp.escape` 与 `Matcher.quoteReplacement`、替换参数传函数。

## 快速运行

```bash
make verify     # 需要 Docker；约 15 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
