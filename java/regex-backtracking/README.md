# 正则回溯

对应文章：[regex-backtracking.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/regex-backtracking.md)。

`src/RegexLab.java` 用一个记录 `charAt` 调用次数的 `CharSequence` 包住输入，把匹配做了多少工作变成确定的数字。同一份代码（只用 Java 8 的语法）在固定 digest 的 temurin 25 与 temurin 8 容器里各编译运行一遍：

1. 教科书里的嵌套量词 `(a+)+b`、`^(\w+\s?)+$`、`^(\d+,?)+$`，输入是能匹配的与结尾差一个字符的；
2. 同样的结构加上反向引用、懒惰的外层量词、第三层嵌套、有界重复；
3. 改写：去掉嵌套、占有量词、原子组、把可选分隔符改成必需分隔符；
4. 查找结尾空白 `\s+$`，输入是大量空格后跟一个非空白字符；
5. 给匹配设 100 万次 `charAt` 的预算；限制输入长度。

调用次数由表达式、输入与 JDK 的实现共同决定，在同一个镜像上是确定的，`scripts/verify.sh` 把两份输出分别与预期逐行比较。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
