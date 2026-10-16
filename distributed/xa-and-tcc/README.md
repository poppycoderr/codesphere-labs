# 两阶段提交与 TCC：准备之后由谁保存决定

对应文章：[xa-and-tcc.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/xa-and-tcc.md)。

`compose.yaml` 启动一个 MySQL 8.4.11（固定 digest，锁等待超时 2 秒）。`src/XaLab.java` 用 mysql-connector-j 9.7.0 连接，分三段运行：`phase1` 在 bank_a、bank_b 两个库上各用一个连接作为两个 XA 分支，演示正常提交、准备之后协调者断开、只提交一个分支后协调者消失；随后 `scripts/verify.sh` 重启 MySQL 容器；`phase2` 查看重启后的 XA RECOVER、行锁，并按协调者的决定提交剩余分支；`tcc` 用三种实现（朴素、只处理空回滚、带分支状态表）各跑空回滚、悬挂、重复确认三个场景，每个场景从干净的数据开始。

## 快速运行

```bash
make verify     # 需要 Docker；约 2 分钟（不含拉取镜像）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
