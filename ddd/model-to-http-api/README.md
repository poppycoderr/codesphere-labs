# 从模型到 HTTP 接口：条件更新、命令与受理

对应文章：[model-to-http-api.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/model-to-http-api.md)。

`src/ApiLab.java` 用 Spring MVC 7.0.9 的 MockMvc 在进程内执行请求，依赖由 `pom.xml` 下载。领域对象是一个订单（地址、备注、状态、版本号），取消有「已发货不能取消」的规则。接口有几种写法：不检查版本的 PUT 整体替换、要求 `If-Match` 的 PUT、PATCH、取消的命令端点、可选 `Idempotency-Key` 的创建、返回 202 的报表生成。请求的先后顺序由代码固定。

## 快速运行

```bash
make verify     # 需要 Docker；约 1 分钟
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
