# 验证记录：集群里的一次请求：认证、授权、准入

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 不带凭据 | system:anonymous：/version 200，列命名空间 403 |
| 去掉 -k | curl 退出码 60；指定集群 CA 后 200 |
| reader 令牌 | 本命名空间 Pod 200，Secret 403，其他命名空间 403；别的受众 401 |
| 令牌有效期 | create token 600 秒；投射令牌一年，约一小时后提示刷新 |
| kubectl proxy | 管理员凭据 200，reader 凭据 403 |
| Secret | base64 解码即明文；etcd 中也是明文 |
| 准入 | restricted 命名空间里管理员的特权 Pod 被拒绝 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- kind 默认配置：匿名认证开启、没有配置 Secret 静态加密；托管集群的默认值可能不同。
- 投射令牌的一年有效期来自 API Server 默认开启的有效期延长（兼容不会刷新令牌的旧客户端），关闭后为 kubelet 请求的时长。
- 单节点集群，所有请求都在控制面节点内发出。

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
