# 知识库的增量更新

对应文章：[rag-index-lifecycle.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/rag-index-lifecycle.md)。

`src/lab.py` 在固定 digest 的 `python:3.14.8-slim` 容器里运行，只用标准库，容器无网络。嵌入是确定性的假实现：把字符二元组按模型名加盐哈希到 64 维再归一化。对比两种索引：只按内容哈希做键的朴素索引（只做增量导入），以及按来源记录块的索引（重建来源时删除它不再产生的块、全量同步时删除已不存在的来源、每条向量记录嵌入模型与权限组、检索前先按权限过滤）。场景依次是：修改文档、删除文档、查询换用另一个嵌入模型或另一种维度、带权限的文档、只用问题文本做键的答案缓存。

## 快速运行

```bash
make verify     # 需要 Docker；约 5 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
