# 低复杂度不等于不需要测试

对应文章：[coverage-is-not-verification.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/coverage-is-not-verification.md)。

三个没有任何分支的计价方法，用 PMD 7.28.0 报告它们的圈复杂度与 NPath，再分别用常见输入和边界输入检查结果。用来说明「复杂度低于某个阈值就可以不写单元测试」这类规则的问题。

## 快速运行

```bash
make verify     # 在固定 digest 的 temurin 25 容器里运行 PMD 与示例程序，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
