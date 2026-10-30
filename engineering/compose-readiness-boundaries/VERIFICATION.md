# 验证记录：Compose 里的依赖就绪、网络与项目边界

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| depends_on 默认 | app 启动时 db 未监听，连接被拒 |
| service_healthy | 等到健康后启动，连接成功 |
| 依赖不健康 | up 失败，app 停在 created |
| ports | 127.0.0.1:18080 与 0.0.0.0:18081 |
| 未 EXPOSE 的端口 | 同一网络内可达 |
| 服务名解析 | 同项目可解析，别的项目不行 |
| 两个项目名 | 各自一套卷和网络 |
| down / down -v | 保留卷 / 删除项目卷，外部卷保留 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 单机 Compose；Swarm 与跨主机网络不在范围内。
- 健康检查只说明 db 在监听端口，真实服务的健康检查要检查它能否处理请求。
- 防火墙与宿主机上 0.0.0.0 绑定实际暴露给哪些网络，取决于宿主机网络配置（Docker Desktop 与 Linux 不同），没有测。

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
