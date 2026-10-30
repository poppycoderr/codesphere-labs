# 验证记录：容器重建之后，数据和初始化还在吗

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 不指定卷、删除重建 | 新匿名卷，数据不见；旧匿名卷留下 |
| 命名卷、删除重建 | 数据还在 |
| 已有数据时改密码 | 旧密码能登录，新密码不能 |
| 初始化脚本 | 首次执行 1 次，第二次 0 次 |
| 宿主机空目录挂载 | 遮住镜像内容（5 → 0） |
| 新命名卷挂载 | 由镜像内容填充（5 个）；之后不随镜像更新 |
| tmpfs | 重启后消失；可写层保留 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只用单机 Docker；Kubernetes 的 emptyDir、PVC 与 StorageClass 回收策略没有覆盖。
- 运行中直接复制数据文件是否能恢复取决于复制时刻，结果不确定，没有作为实验断言，文中按文档说明。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-30 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
