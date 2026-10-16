# 验证记录：从行为到场景到断言：失败信息、分组断言与重构安全

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `assertTrue(items.contains("banana"))` | expected: <true> but was: <false> |
| `assertEquals(List.of(...), items)` | 同时给出期望与实际的列表 |
| AssertJ `contains` | 给出实际列表与缺少的元素 |
| AssertJ 加 `as("alice 的购物车")` | 失败信息带上描述 |
| 订单摘要两个字段错误，逐条 `assertEquals` | 只报出第一个（total） |
| `assertAll` / `SoftAssertions` | 一次报出两个（total、status） |
| 参数化测试带 `name` 模板 | 失败的是「"10" 公斤 → 运费 "20"（"恰好 10 公斤"）」 |
| 参数化测试不带 `name` | 失败的是「[2] "10", "20"」 |
| 计价从第一版重构到第二版，断言总额 | 两版都通过 |
| 同上，`verify(tax, times(3)).rate()` | 第一版通过，第二版失败（实际 1 次） |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 失败信息的格式取决于 JUnit、AssertJ、Mockito 的版本；升级后措辞可能变化。
- 显示名进入 XML 报告依赖 surefire 的 `usePhrasedTestCaseMethodName` 配置；IDE 里的显示不受这个配置影响。

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
