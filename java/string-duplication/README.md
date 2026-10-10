# 内容相同的字符串

对应文章：[string-duplication.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/string-duplication.md)。

`src/StringLab.java` 在固定 digest 的 temurin 25 容器里运行。模拟从外部读入 200 万条记录，每条有一个约 20 个字符的字段，取值只有 100 种；每次解析都从字节新建一个 `String`。五种做法各在一个新的子进程（G1，堆上限 1 GB）里运行，构建完成后多次触发垃圾回收，用构建前后已用堆的差值减去引用数组，除以记录数，得到每个字段平均占用的字节数。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```
