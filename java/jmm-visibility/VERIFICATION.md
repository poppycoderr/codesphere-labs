# 验证记录：JMM 可见性

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **默认参数、普通字段**：主线程写入 `stop = true` 三秒后，工作线程仍在循环，3 次都是 `worker alive after 3s: true`。
2. **`-Xint` 解释执行、普通字段**：3 次都正常退出。
3. **默认参数、`volatile` 字段**：3 次都正常退出。

结论：没有 happens-before 关系时，规范不要求读线程看到写入，JIT 编译后把循环条件当成不变量读一次是合法的。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。JDK 21.0.5 HotSpot，10 核 Apple Silicon。

## 三、执行步骤

`scripts/verify.sh` 对三种方式各运行 3 次 `src/StopFlag.java`，每次取最后一行输出。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/output.tsv` | 9 次运行的结果 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 默认参数下停不下来，取决于 JIT 是否把字段读取提出循环。换 JDK 版本、平台或 JIT 参数，结果可能不同；能确定的只有「规范不保证可见」。
- `-Xint` 下能退出，不代表解释执行提供了可见性保证，只说明这次运行没有做这项优化。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 与文章表格一致，补「配套实验」链接 |
