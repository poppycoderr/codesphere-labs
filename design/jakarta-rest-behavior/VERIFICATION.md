# 验证记录：Jakarta REST 的默认行为

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 返回 String | 200 |
| 返回 null、void 方法 | 204 |
| 返回 `Response.status(201)` 带头 | 201，头保留 |
| 只有 `@GET` 时发 HEAD | 200，无响应体，GET 方法被调用 1 次 |
| 默认 OPTIONS | 200，`Allow: GET,HEAD,OPTIONS,PUT` |
| 对不存在的路径发 OPTIONS | 404 |
| 对只有 GET/PUT 的资源发 DELETE | 405，`Allow: GET,OPTIONS,PUT` |
| `WebApplicationException(409)` | 409，实体保留 |
| 有映射器的异常 | 映射器给出的 422 |
| 映射器自身抛异常 | 500 |
| 未映射的 RuntimeException、没有写出器的返回类型 | 500 |
| `@QueryParam int` 缺省、空串 | 0 |
| `@QueryParam Integer` 缺省 | null |
| `@DefaultValue("5")` | 5 |
| `@QueryParam`/`@PathParam` int 传 abc | 404 |
| `@HeaderParam` int 传 abc | 400 |
| 同时有 String 构造器、valueOf、fromString | 用 valueOf |
| 只有 valueOf 与 fromString | 用 valueOf |
| 枚举另写 fromString | 用 fromString |
| 转换构造器抛 IllegalArgumentException | 查询参数 404，请求头 400 |
| `List<Integer>` 含非法元素 | 404 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证 Jersey 4.0.3 与 JDK `HttpServer` 容器；其他实现（RESTEasy、Open Liberty 等）和 Servlet 容器的错误页、`Content-Length` 等可能不同。
- 规范只列出可接受的参数类型（ParamConverter、primitive、String 构造器、valueOf/fromString），没有规定构造器与静态方法的先后；「选 valueOf」是 Jersey 4.0.3 的实现行为。
- 未覆盖媒体类型协商、子资源定位器、异步响应和过滤器。

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
