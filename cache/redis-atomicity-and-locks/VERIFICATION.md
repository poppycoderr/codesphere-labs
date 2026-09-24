# 验证记录：Redis 原子性、事务与锁

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论

1. 「`GET` 判断后 `SET`」在并发下超卖；`DECR` 后补偿、`WATCH` 重试、Lua、Function 都恰好卖出 100 个，WATCH 在高冲突下大量重试。
2. MULTI 入队时的错误使 `EXEC` 返回 `EXECABORT`、整个事务不执行；执行时的错误只影响出错的那条命令，其余命令生效，不回滚；`WATCH` 的 key 被修改时 `EXEC` 返回空。
3. 重启后 `EVALSHA` 返回 `NOSCRIPT`，`FCALL` 仍可执行。
4. 不校验 token 的 `DEL` 会删掉别人的锁；比较 token 的 Lua 释放不会。
5. 租约过期后，旧持有者恢复并写入下游，覆盖新持有者的结果；下游校验 fencing token 时，旧持有者的写入被拒绝。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt) 与 [`evidence/container.txt`](evidence/container.txt)：Redis 8.10.1（`compose.yaml` 固定 digest），`--appendonly yes --save ''`，2 CPU、1 GB；JDK 21（固定 digest）。

## 三、执行步骤

`src/Atomicity.java` 的 `main` 模式依次执行：

1. 加载 Function 库 `quota` 与 Lua 脚本；每种写法前把名额设为 100、清空名单，50 个线程就绪后同时开始，各抢 10 次；记录客户端认为抢到的次数、名单长度、剩余名额、WATCH 重试次数与耗时。
2. 事务：`MULTI` 中加入参数个数错误的 `INCRBY`；`MULTI` 中对字符串执行 `LPUSH`，前后各有一条 `SET`；`DISCARD`；另一个连接在 `EXEC` 前修改被 `WATCH` 的 key。
3. 锁：A 以 300ms 租约加锁，B 获取失败；过期后 B 获取成功，A 直接 `DEL`；B 重新加锁后 A、B 分别用比较 token 的 Lua 释放。
4. 租约：A 以 500ms 租约加锁并 `INCR` 领 token，睡眠 1500ms 后写下游；B 在第 700ms 加锁、领 token、写下游。分别在不校验 token 与校验 token（`FENCED_WRITE`）两种下游下运行。

然后 `before-restart` 模式加载 Function 与脚本并各调用一次，`docker compose restart` 后 `after-restart` 模式再调用。

## 四、证据与实际结果

| 文件 | 内容 |
|---|---|
| `evidence/main.tsv`、`before-restart.tsv`、`after-restart.tsv` | 每一步的返回值与计数，租约场景的毫秒时间线 |
| `evidence/*.log` | 程序标准输出 |
| `evidence/assertions.txt` | 断言结果 |

主要结果（`evidence/assertions.txt`）：

- `GET` 后 `SET`：客户端认为抢到 500 次，名单 500 人，剩余 34；`DECR` 补偿 9ms、WATCH 115ms（重试 3,003 次）、Lua 5ms、Function 4ms，都恰好 100 人。
- 入队错误：`EXECABORT`，`SET` 未执行；执行时错误：`[OK, WRONGTYPE…, OK]`，两条 `SET` 都生效；WATCH 冲突：`EXEC` 返回空。
- 重启后 `EVALSHA` 返回 `NOSCRIPT`，`FCALL` 执行成功。
- A 的 `DEL` 返回 1 并删掉 B 的锁；比较 token 的释放：A 返回 0，B 返回 1。
- 没有 fencing：最终值为 `written-by-A`；有 fencing：A 的写入返回 0，最终值为 `written-by-B`。

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/summarize.py`。

## 五、误差、限制与不能推出的结论

- 耗时来自同一台机器上的回环连接，只说明相对关系；WATCH 的重试次数取决于并发度与调度。
- 「下游」是 Redis 中的一段 Lua，模拟数据库的条件更新，没有启动真正的数据库。
- 停顿用线程睡眠模拟；没有覆盖主从切换导致的锁丢失、时钟跳变与 Redlock。
- `DECR` 补偿写法期间计数器会短暂为负，本实验只检查了最终结果。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
