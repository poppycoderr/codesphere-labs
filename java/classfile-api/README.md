# Class-File API 与类文件版本边界

对应文章：[bytecode-libraries.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/bytecode-libraries.md)。

1. `src/ClassFileDemo.java` 在 temurin 25.0.4 上用 `java.lang.classfile` 从零生成一个类并调用；读入 `javac --release 21` 编译的 `Target.class`，把方法里的字符串常量 `"old"` 换成 `"new"`，重新加载后调用；
2. 用 JDK 26 的 `javac --release 26` 编译同一个类（主版本号 70），分别交给 JDK 25 与 JDK 26 的 Class-File API、ASM 9.10.1 与 ASM 9.7.1 解析。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（首次需要下载 ASM）
make evidence
make clean
```
