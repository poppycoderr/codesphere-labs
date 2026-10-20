# 验证记录：声明式 HTTP 客户端

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `createClient` 返回的对象 | JDK 动态代理 |
| `@PathVariable` 不写名字（单文件启动，没有 `-parameters`） | IllegalArgumentException：参数名不可用 |
| `get("a/b c")` | 原始路径 `/users/a%2Fb%20c` |
| `search("x&y+z", ["a","b"])` | 原始查询串 `q=x%26y%2Bz&tag=a&tag=b` |
| 接口默认方法 `twice("7")` | 服务端收到 2 个请求 |
| 服务端 404（返回 String 或 ResponseEntity） | 都抛 `HttpClientErrorException$NotFound` |
| 服务端 500 | 抛 `HttpServerErrorException$InternalServerError` |
| 默认请求工厂，服务端 3 秒后响应 | 等了约 3 秒 |
| 读取超时 1 秒 | 约 1 秒后 `ResourceAccessException` |
| 手写代理直接替换，`get("a/b")` | 路径变成 `/users/a/b` |
| 手写代理直接替换，`get("a b")` | `IllegalArgumentException`：路径里有非法字符 |
| 手写代理用 URLEncoder 编码路径，`get("a b")` | 路径变成 `/users/a+b` |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 Spring Framework 7.0.9 的 `RestClient` 适配器与 JDK 请求工厂；`WebClient` 适配器、其他请求工厂（Apache、Jetty、Reactor Netty）的默认超时与异常类型不同。
- 接口必须是 public：实验第一次运行时把接口写成包级私有的嵌套接口，调用默认方法时 Spring 报 `IllegalAccessException`，改为 public 后正常。
- 404 也抛异常是 `RestClient` 默认状态处理器的行为，可以通过 `defaultStatusHandler` 改变；本实验只验证默认值。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-20 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
