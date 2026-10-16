# 验证记录：替身证明了什么：匹配、契约与录制响应

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `when(repo.find(any()))`，宽松模式 | 通过，缺陷没有被发现 |
| `when(repo.find("o-1"))`，宽松模式 | 错误：订单不存在 |
| 同上，严格桩（MockitoExtension 默认） | 错误：Strict stubbing argument mismatch，指出实际调用是 `find("c-9")` |
| 契约测试：按客户查询倒序 | H2 通过；内存替身失败（按插入顺序返回） |
| 契约测试：重复 id 被拒绝 | H2 通过；内存替身失败（直接覆盖） |
| 录制响应 `{"status":"PAID"}` 的替身 | 通过 |
| 本地服务返回新格式 `{"state":"PAID"}` | 失败：解析为 UNKNOWN |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 「对方改了格式」由本地 `HttpServer` 模拟；真实场景里还需要消费者驱动的契约或与提供方共享的契约测试，实验没有覆盖。
- H2 代表「真实实现」只用于展示契约测试的做法；生产数据库的行为（排序规则、隔离级别）应当用相同的数据库验证。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
