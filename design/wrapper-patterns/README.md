# 包装类模式

对应文章：[wrappers-adapter-decorator-proxy.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/wrappers-adapter-decorator-proxy.md)。

单文件 `src/Wrappers.java`，围绕同一个 `NotificationGateway`：

- 两种适配器翻译模拟的短信供应商：全部翻译成同一个异常，或区分可重试与不可重试并保留错误码；外面都包一个最多尝试 3 次的重试装饰器；
- 计量、缓存、重试三个装饰器的两种叠加顺序；
- JDK 动态代理：自调用绕过代理、未声明的受检异常被包装。

Spring AOP 的自调用、`final` 方法等失效场景见 [spring/aop-proxy-pitfalls](../../spring/aop-proxy-pitfalls/)，装饰器与动态代理的栈帧数见 [design/jdk-api-facts](../jdk-api-facts/)。

## 快速运行

```bash
make verify     # 需要 JDK 21
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物
```
