# URI 模板编译成正则

对应文章：[uri-template-regex.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/uri-template-regex.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里运行，对比三种做法：朴素编译（字面部分原样拼进正则，变量按出现顺序编号取组）、改正后的编译（字面部分 `Pattern.quote`，变量用命名组）、Jersey 4.0.3 的 `org.glassfish.jersey.uri.UriTemplate`。另外验证 `String.replaceAll` 替换串里 `$` 与 `\` 的含义、`Matcher.quoteReplacement` 的作用，按 Jersey `UriTemplate.COMPARATOR` 与按字符串长度两种方式排序候选模板时各路径由谁处理，以及两个比较器是否满足反对称性。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
