# 候选、确认与生效：抽取结果与模型发布

对应文章：[candidate-confirmation-activation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/candidate-confirmation-activation.md)。

用一个 SQLite 文件模拟两条路径：从文档里抽取出的金额候选怎样进入正式记录，训练出的模型版本怎样被部署指针引用。两者使用同一组约束：候选绑定来源的内容哈希，确认与切换按版本条件更新，命令带幂等回执，异步回调匹配尝试号。抽取结果是写死的合成数据，没有调用 OCR 或任何模型，也没有加载或部署真实模型。

## 快速运行

```bash
make verify     # 在固定 digest 的 Python 3.14.8 容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
