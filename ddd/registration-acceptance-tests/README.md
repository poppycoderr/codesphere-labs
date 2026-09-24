# 事件风暴产物进入验收测试

对应文章：[event-storming-from-events-to-rules.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/event-storming-from-events-to-rules.md)，[domain-modeling.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/domain-modeling.md) 也引用本实验的规则表与词汇表。

虚构的活动报名平台。`model/` 保存一次事件风暴讨论的整理稿：事件时间线、热点、规则表（R1—R8）和词汇表。`src/` 里有两个模型：

- `DraftSession`：照着第一次讨论的便利贴写出的初稿，没有「同一个人重复报名」这条规则；
- `Session`：补上热点 H1 之后的修订模型。

`RegistrationAcceptanceTest` 的 12 个 Given/When/Then 用例只依赖 `RegistrationModel` 接口，用例名以规则编号开头。`scripts/trace.py` 检查每条规则都有用例、每个术语都能在代码中找到。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven；首次下载依赖
make evidence   # 重新采集 evidence/
make clean
```
