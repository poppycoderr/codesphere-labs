# 验证记录：百万行导入的写入方式与失败语义

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 逐行自动提交最慢；只用批处理、不开启 `rewriteBatchedStatements` 已快数倍；开启后再快数倍
2. 批大小从 1000 加到 5000 提升有限（不到 1.5 倍）
3. 4 个线程快于单线程；`LOAD DATA LOCAL INFILE` 快于单线程重写批处理
4. 一批 6 行、第 4 行主键冲突，捕获异常后提交：未开启重写时 `getUpdateCounts()` 为 `[1, 1, 1, -3, 1, 1]`，其余 5 行写入；开启重写时全部为 `-3`，一行都没有写入

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：mysql 8.4.11（`compose.yaml` 固定 digest，默认配置，开启 binlog），JDK 21（`eclipse-temurin:21-jdk` 固定 digest），MySQL Connector/J 8.0.27。

## 三、执行步骤

`scripts/verify.sh` 依次执行：

1. 启动默认配置的 MySQL，开启 `local_infile`
2. 在与 MySQL 共享网络的 JDK 容器中运行 `src/ImportBench.java`：每种写入方式重新建表，采样 3 次
3. 失败语义：两种驱动设置下各写一批 6 行，捕获 `BatchUpdateException` 后提交，读出表中的数据
4. `scripts/summarize.py` 取中位数生成 `summary.md` 并断言相对关系

每一项结论都有对应的断言，任何一项不满足即返回非零退出码。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/import-bench.txt` | 每种方式每次采样的耗时与吞吐，以及失败语义的输出 |
| `evidence/summary.md` | 中位耗时、吞吐与相对倍数 |
| `evidence/server.txt` | 版本与持久化参数 |
| `evidence/assertions.txt` | 断言结果 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/`。

## 五、误差、限制与不能推出的结论

- 吞吐与硬件、磁盘刷盘速度强相关，只断言相对关系；3 次采样的离散度见 `summary.md`。
- `LOAD DATA` 与多线程的先后顺序会随 CPU 数变化：文章发布时的环境中 4 线程略快，本次 2 CPU 的容器中 `LOAD DATA` 更快。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 文章发布时 | 在 JDK 21 + MySQL 8.4.11 上实测，数字见文章 | — |
| 2026-09-24 | 迁入本仓库，默认配置下 3 次采样重跑 | 是：逐行 1,956 → 1,923 行/秒，重写批处理 134,373 → 143,266，4 线程 288,579 → 210,970，LOAD DATA 272,350 → 332,779；批大小 5000 的提升 14% → 8% |
