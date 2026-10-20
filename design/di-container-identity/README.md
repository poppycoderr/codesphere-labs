# 依赖注入容器的绑定身份与依赖图

对应文章：[di-container-identity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/di-container-identity.md)。

`src/Lab.java` 是一个约 150 行的最小依赖注入容器，在固定 digest 的 temurin 25 容器里运行，注解来自 `jakarta.inject-api` 2.0.1。容器可以切换两种绑定身份：只按 `Class`，或按「类型 + 限定符」的 `Key`；配置期检查沿立即依赖做深度优先并维护当前路径，`Provider<T>` 视为延迟边只检查目标是否注册；运行期解析记录正在构造的键以发现重入。依次运行：同类型两个限定绑定、自己依赖同类型另一个绑定的装饰、真正的环、菱形依赖（路径栈与只用已访问集合两种判断）、延迟边与构造器里立刻 `get()`、缺失依赖的路径、匿名子类捕获泛型的引用相等性。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
