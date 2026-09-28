# Maven scope 与三种类路径

对应文章：[maven-scope.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/maven-scope.md)。

`pom.xml` 里四个依赖各用一种 scope：commons-logging（compile）、MapStruct（provided）、Javassist（runtime）、ASM（test）。`src/main/java/labs/App.java` 用到了 provided 的 MapStruct。

1. 用 `dependency:list -DincludeScope=compile|runtime|test` 列出三种类路径；
2. 用 `dependency:build-classpath -DincludeScope=runtime` 得到运行时类路径，只用它运行 `App`。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven；约 30 秒（首次需要下载依赖）
make evidence
make clean
```
