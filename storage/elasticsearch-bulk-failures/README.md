# bulk 的部分失败、重试与版本号

对应文章：[elasticsearch-bulk-failures.md](https://github.com/poppycoderr/codesphere/blob/master/docs/storage/elasticsearch-bulk-failures.md)。

单节点 Elasticsearch 9.5.5，索引映射为 `dynamic: strict`。客户端只用 Python 标准库调用 REST 接口，验证 bulk 的逐项错误、两种重试方式、`create` 冲突，以及不带版本号和带外部版本号时乱序写入的结果。

## 快速运行

```bash
make verify     # 启动 Elasticsearch 容器，在 Python 3.14.8 客户端容器里运行，逐行比较输出
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。

新增：write=1/queue_size=1、24 并发 bulk 触发真实逐项 429，保存原始响应、队列计数与失败项重试轨迹。另有禁用周期刷新的 GET 可见性对照，不据此判断引擎内部分支。
