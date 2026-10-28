# 验证记录：Seata AT 模式：分支已经提交的全局事务

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 全局提交前 | 不经 Seata 的连接已读到分支写入 |
| 全局回滚 | 按前镜像恢复，状态 Finished |
| 绕过 Seata 改了同一行后回滚 | RollbackFailed，数据停在 75，undo_log 保留 |
| 回滚失败之后 | 全局锁不释放，新的全局事务拿锁超时 |
| 两个全局事务改同一行 | 第二个 LockConflictException；绕过 Seata 的写入不受限 |
| 超时的全局事务 | commit() 不抛异常，数据已被回滚 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 单个 TC（file 存储模式）、单个资源；没有跨服务调用，分支都在同一个进程里。
- 使用 TM API 而不是 @GlobalTransactional 注解，注解模板对超时结果的处理没有覆盖。
- 全局锁的重试间隔与次数由 config/file.conf 设置为 10ms × 30。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-28 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
