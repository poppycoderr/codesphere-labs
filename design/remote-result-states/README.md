# 远程调用结果的四种状态

对应文章：[remote-result-states.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/remote-result-states.md)。

`src/Lab.java` 在同一个 JVM 里用 `com.sun.net.httpserver.HttpServer` 模拟商品服务与订单服务（统一返回 200 + {code, data}、IN 批量查询按主键返回、下单处理很慢但已落库），对比两种客户端：把非成功都变成 null/空列表/false 的朴素客户端，以及返回 Found / NotFound / Failed / Unknown 的有类型客户端。客户端超时 200ms，慢响应 500ms。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
