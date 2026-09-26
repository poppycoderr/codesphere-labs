# 类加载与 SPI

对应文章：[class-loading-and-spi.md](https://github.com/poppycoderr/codesphere/blob/master/docs/java/class-loading-and-spi.md)。

单文件程序 `src/ClassLoading.java` 在运行时用 `javax.tools.JavaCompiler` 把几组源码编译到 `build/tmp` 的不同目录，再用不同的 `URLClassLoader` 加载，观察六件事：

1. 两个加载器加载同一份 `com.example.Plugin`，类型是否相同，转换时的报错；
2. 读取编译期常量、创建数组、`Class.forName(name, false, loader)`、读取 `Integer` 常量，哪一步触发静态初始化，以及初始化过程中读到的准备阶段零值；
3. 接口在父加载器、实现在子加载器时，`ServiceLoader` 能否找到实现，线程池工作线程的上下文类加载器；
4. 父优先与子优先加载同名库类，自定义加载器能否定义 `java.lang` 下的类；
5. 插件加载器在三种引用下能否被回收；
6. 按新版本 API 编译、运行时是旧版本。

## 快速运行

```bash
make verify     # 需要 JDK 21；约 10 秒
make evidence
make clean
```
