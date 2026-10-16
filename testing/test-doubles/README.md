# 替身证明了什么：匹配、契约与录制响应

对应文章：[test-doubles.md](https://github.com/poppycoderr/codesphere/blob/master/docs/testing/test-doubles.md)。

一个 Maven 项目。`Orders.java` 定义订单仓储接口（契约：重复 id 抛异常、按客户查询时按创建时间倒序）、一个测试里常用的内存实现、一个 H2 实现，以及一个有缺陷的取消服务（用客户 id 去查订单）。`PaymentClient.java` 按录制时的响应格式解析支付状态。测试分三组：Mockito 的宽泛匹配、精确参数与严格桩；同一组契约测试分别跑内存实现和 H2 实现；录制响应的替身与一个返回新格式的本地 HTTP 服务（JDK `HttpServer`）。`scripts/verify.sh` 在容器里运行 `mvn test`（失败不中断），再由 `src/Report.java` 汇总 surefire 报告。

## 快速运行

```bash
make verify     # 需要 Docker；约 2 分钟（首次需下载依赖）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
