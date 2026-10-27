# 验证记录：Spring MVC 请求经过的各层

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| @ResponseBody 返回 Map（Jackson） | postHandle 时未提交，四个响应头都在 |
| ResponseEntity<String> | postHandle 时已提交，postHandle 与 Filter 之后加的头丢失 |
| 处理器抛 IllegalStateException，Advice 处理 | 409；postHandle 不执行；afterCompletion 的 ex 为 null |
| preHandle 返回 false | 200，空响应体，处理器不执行 |
| Filter 里抛异常 | 500，响应体不是 Advice 生成的；错误转发经过拦截器 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/dependencies.txt`](evidence/dependencies.txt)。

## 三、执行步骤

`mvn test` 运行 `src/test` 下的 JUnit 测试，测试把观察到的事实写入 `target/facts.tsv`；`scripts/verify.sh` 逐行断言。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 响应是否在 postHandle 前提交取决于消息转换器是否刷新输出流与响应体大小（超过缓冲区会提前提交），本实验只覆盖 Jackson 3 写小对象与 String 转换器两种。
- 错误页由 Spring Boot 的错误处理（/error 转发）生成，实验里的 Filter 只处理 REQUEST 类型的分派。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，全部断言通过 | 新文章，结论取自本次证据 |
