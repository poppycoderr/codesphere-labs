# 单例作用域的缓存所有权

对应文章：[scope-cache-ownership.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/scope-cache-ownership.md)。

`src/Lab.java` 在固定 digest 的 temurin 25 容器里运行，用一组极简的 Provider 与作用域装饰器比较：配置浅复制与按配方重建两种建容器方式下单例的范围；两个限定符绑定同一装饰器与各自绑定；不加锁与加锁的单例在两个线程同时第一次取值时的创建次数（用 `CountDownLatch` 让两个线程都进入创建过程再继续）；工厂返回 null 时用 `instance == null` 与独立标志判断的差别；第一次创建抛异常后的行为；作用域装饰器是否转发被包装组件的依赖元数据对配置期环检查的影响。

## 快速运行

```bash
make verify     # 需要 Docker；约 20 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
