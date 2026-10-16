# 验证记录：Spring 测试事务的失真

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 测试事务（回滚） | 不加测试事务（提交） |
|---|---|---|
| 同一邮箱注册两次，断言不抛异常 | 通过 | 失败：DataIntegrityViolationException，唯一索引冲突 |
| 加载订单后在服务外读订单行 | 通过 | 错误：Cannot lazily initialize collection … (no session) |
| 注册后断言通知已发出 | 失败：通知列表为空 | 通过 |
| 下单后另一个线程能否看到订单 | 失败：看不到 | 通过 |
| 前一个测试类回滚后审计表的行数 | — | 3（REQUIRES_NEW 已提交，不随回滚撤销） |
| 应用上下文个数（A、B、Z 配置相同，C 替换了一个 Bean） | 2 | |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 唯一约束的场景依赖「id 由应用指定、插入推迟到 flush」；使用数据库自增主键时，Hibernate 在 `persist` 时立即插入，约束会在测试事务里就触发。
- H2 代表真实数据库只用于演示事务边界；隔离级别、约束检查时机在不同数据库上可能不同。
- 「另一个线程」用 `CompletableFuture` 的公共线程池模拟，与 `@Async`、消息消费者在事务可见性上的表现相同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
