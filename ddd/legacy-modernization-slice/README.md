# 遗留系统的精益切片

对应文章：[adoption-and-legacy-modernization.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/adoption-and-legacy-modernization.md)。

遗留的 `LegacyRegistrationService` 把报名、取消和退款计算写在一个类里，状态是整数，行是 `Map`。要迁移的切片是「取消时的退款规则」。四步：

1. **字符化测试**：2000 个随机用例加 7 个边界用例，旧服务的输出保存为 `src/test/resources/approved-refunds.tsv`，之后每次运行都必须完全一致；
2. **切出接缝**（修缮式的第一步）：把退款计算原样移到 `RefundCalculator` 接口后面，字符化测试仍然通过；
3. **影子比对**：新模型的 `RefundPolicy` 与旧算法并行计算，只比对、不生效；
4. **按比例分流**（绞杀式）：`RefundRouter` 按报名 id 的哈希把 10%、50%、0%、100% 的退款交给新模型。

`-Dapprove=true` 会用旧服务的当前输出重写已批准文件，只在确认旧行为本身需要改变时使用。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven
make evidence
make clean
```
