# 熔断器的默认配置

对应文章：[circuit-breaker-defaults.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/circuit-breaker-defaults.md)。

`src/BreakerLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，依赖 Resilience4j 2.4.0 的 `resilience4j-circuitbreaker`。每个场景新建一个熔断器，用 `tryAcquirePermission` 申请放行，再用 `onSuccess` / `onError` 上报结果和耗时；调用耗时是直接上报的数值，不真的等待。只有「打开后的等待时间」用了 200 毫秒的真实等待。

覆盖：默认配置的各项取值；最少调用数；打开之后的拒绝；窗口里有成功记录时需要多少次失败；打开到半开的时机、半开时的放行数、试探调用不返回时的状态；业务异常是否算失败；慢但不报错的调用；两个接口共用一个熔断器；十个实例里一个失败。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 20 秒
make evidence
make clean
```
