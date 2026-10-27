# 验证记录：拦截链的三条契约

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 认证检查因缺少请求头抛 NullPointerException | 朴素链（只把 Denied 当拒绝）执行业务；失败即停止的链拒绝 |
| 审核服务超时 | 朴素链执行业务；失败即停止的链拒绝 |
| 黑名单按 X-User 判断而请求没有该头 | null 不在黑名单里，执行业务；缺少身份直接拒绝 |
| 上限 2、两个占用期间来 3 个请求，finally 无条件 release | 放进 2 个，结束后可用许可 3 |
| 只在拿到许可后 try/finally 释放 | 放进 0 个，结束后可用许可 2 |
| for-each 中删除 node-b、node-c | 无异常，结果 [node-a, node-c] |
| 过滤器直接修改共享候选列表 | 下一个请求只看到 [node-a, node-c]；按请求复制则不受影响 |
| 首个匹配，/api/** 在 /api/admin/** 之前 | /api/admin/users 被放行；按具体程度排序后拒绝 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 检查器、链与规则都是为对比写的极简实现，不代表任何框架的默认行为；Spring Security、网关等各有自己的失败处理与规则排序。
- for-each 删除元素的现象取决于 ArrayList 迭代器的实现（hasNext 只比较下标与 size），删除其他位置时会抛 ConcurrentModificationException。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
