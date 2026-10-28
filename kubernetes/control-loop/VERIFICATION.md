# 验证记录：声明式 API 背后是控制循环

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| ReplicaSet 改成 1 | 5 秒内被改回 3 |
| 删除 Pod | 被补上，3 个 |
| 停掉控制器后改模板 | generation=2，observedGeneration=1 |
| 停掉控制器后删 Pod | 剩 2 个，不补；不建新 ReplicaSet；conditions 仍 Available |
| 恢复控制器 | 一次补齐：3 个就绪 Pod，observedGeneration=2 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 单节点 kind 集群；停止控制器用移走静态 Pod 清单的方式，等价于控制器进程不可用。
- 控制器读本地缓存可能滞后，本实验没有单独测量。

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
