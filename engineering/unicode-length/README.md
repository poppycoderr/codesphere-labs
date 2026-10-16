# 一个字符串有多长：字节、码点、UTF-16 与字形

对应文章：[unicode-length.md](https://github.com/poppycoderr/codesphere/blob/master/docs/engineering/unicode-length.md)。

`src/Uni.java`、`src/uni.py`、`src/uni.js` 是同一组用例，分别在固定 digest 的 temurin 25、python:3.14.8-slim、node:24.21.0-slim 容器里运行，输出按「用例名、语言」排序后逐行比较。七个字符串：中、预组合的 é、e 加组合重音、笑脸、一家三口（ZWJ 序列）、中国国旗（两个区域指示符）、带肤色的点赞；分别统计各语言的默认长度、码点数、UTF-16 单元数、UTF-8 字节数与字形数（Java 用 `BreakIterator`，JS 用 `Intl.Segmenter`，Python 标准库不支持）。另有截断、反转、规范化与大小写转换。

## 快速运行

```bash
make verify     # 需要 Docker；约 15 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
