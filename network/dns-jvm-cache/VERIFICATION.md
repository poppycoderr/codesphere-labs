# 验证记录：DNS 切换与 Java 客户端

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **JVM 不看 DNS 记录的 TTL**：记录 TTL 为 5 秒，改写记录后 `InetAddress.getByName` 返回新地址的时间：

| `networkaddress.cache.ttl` | 解析到新地址 |
|---|---:|
| 未设置（默认） | 约 30.2 秒 |
| 5 | 约 5.1 秒 |
| 0 | 约 0.6 秒（CoreDNS 每秒重新加载一次文件） |

2. **查不到的结果也会被缓存**：先解析一个还不存在的名字，得到 `UnknownHostException`；随后加上记录，约 10.2 秒后才能解析到（`networkaddress.cache.negative.ttl` 默认 10 秒）。
3. **连接复用**（`networkaddress.cache.ttl=0`，每 500ms 请求一次，第 3 秒改写记录，第 20 秒让 backend-1 开始返回 `Connection: close`）：

| 客户端 | 改写记录之后、旧后端关闭连接之前 | 旧后端关闭连接之后 |
|---|---|---|
| JDK `HttpClient` | backend-1 1 次，backend-2 32 次 | 全部 backend-2 |
| `HttpURLConnection` | 全部 backend-1（34 次） | backend-1 1 次，backend-2 38 次 |

JDK `HttpClient` 在解析结果变化后，新请求立刻建立到新地址的连接；`HttpURLConnection` 的 keep-alive 连接按主机名复用，只要连接一直在用，就一直连着旧地址，直到对端关闭连接。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。客户端与后端是 eclipse-temurin 21.0.12（固定 digest），CoreDNS 1.14.7（固定 digest）。客户端容器的 DNS 经 Docker 内置解析器转发给 CoreDNS。

## 三、执行步骤

`scripts/verify.sh` 启动 DNS 与两个后端，依次运行 `resolve`（三种设置）、`negative`、`pool`；重启两个后端（清除 drain 状态）后运行 `pool-urlconnection`。每个场景在独立的 JVM 里运行，缓存设置在第一次解析之前通过 `Security.setProperty` 设定。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 30 秒是 HotSpot 在未设置 `networkaddress.cache.ttl`、没有安装安全管理器时的默认值；规范只说「由实现决定」。
- `networkaddress.cache.ttl=0` 时的约 0.6 秒来自 CoreDNS 的文件重新加载间隔，不代表真实 DNS 的传播时间。真实环境里还有递归解析器、操作系统的缓存。
- 只验证了 HTTP/1.1 明文。数据库连接池、消息客户端等长连接不在本实验范围内，它们何时重新解析取决于各自的实现（通常是连接重建时）。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-27 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
