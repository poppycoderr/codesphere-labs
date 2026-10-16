# 重复的知识与偶然的相似

对应文章：[duplication-and-extraction.md](https://github.com/poppycoderr/codesphere/blob/master/docs/refactoring/duplication-and-extraction.md)。

`src/DuplicationLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行。每个场景把改之前与改之后的写法放在一起执行：同一条规则（税率）写在两处时只改了一处；两条只是今天长得一样的规则（昵称、优惠码）被合并后按其中一条的需求修改；把重复的「执行并通知失败」提取成公共方法后的失败语义；把条件里的调用提取成方法参数后的求值次数。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
