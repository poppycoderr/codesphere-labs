# 验证记录：短链服务的四个契约问题

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **生成方式**：
   - 自增 ID 转 Base62：`1000000000`—`1000000004` 得到 `15FTGg`、`15FTGh`、`15FTGi`、`15FTGj`、`15FTGk`，连续可猜；
   - 随机 7 位 Base62（空间约 3.5 万亿）：100 万个无重复；1,000 万个重复 11 个，生日界估计 14.2 个——靠唯一索引重试就能处理；
   - SHA-256 截取 32 位：10 万个无碰撞，100 万个碰撞 105 个（估计 116），1,000 万个碰撞 11,823 个（估计 11,642）——规模 ×10，碰撞约 ×100。
2. **同一个长 URL**（20 个并发请求）：「先查再写」表里 20 行，调用方拿到 20 个不同的短码；唯一约束写法表里 1 行，所有调用方拿到同一个短码。
3. **跳转方法**：JDK HttpClient 与 curl 都在 301、302 时把 POST 改成 GET 并丢掉请求体（目标收到 `GET 0`），307、308 保留 POST 与请求体（`POST 3`）。
4. **目标地址校验**：

| 目标 | 结果 |
|---|---|
| `https://example.com/a?b=1` | 放行 |
| `javascript:alert(1)` | 拒绝：协议 javascript |
| `data:text/html,<script>…` | 拒绝：不是合法 URI |
| `ftp://example.com/file` | 拒绝：协议 ftp |
| `http://127.0.0.1/admin`、`http://localhost/admin` | 拒绝：解析到 127.0.0.1 |
| `http://169.254.169.254/latest/meta-data/` | 拒绝：链路本地地址 |
| `http://[::1]/` | 拒绝：回环 |
| `http://internal.example/`（解析到 10.1.2.3） | 拒绝：按解析结果判断 |
| `http://example.com@127.0.0.1/` | 拒绝：包含 userinfo |
| `http://2130706433/` | 拒绝：Java 按十进制 IP 解析为 127.0.0.1 |
| `http://0x7f000001/` | 拒绝：Java 不认十六进制写法，按解析失败处理 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。MySQL 8.4.11，JDK 21 容器，curl 8.18.0。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/ShortUrl.java`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 「先查再写」产生几行取决于调度（调试运行中见过 3 行），断言只要求多于 1 行。
- 浏览器会把 `0x7f000001` 解释为 127.0.0.1，而 Java 不会：校验必须对解析失败的名字直接拒绝，不能放行；本实验只验证了 Java 这一侧。
- 跳转只测了两个命令行客户端；301 被浏览器缓存、导致之后的点击不再经过短链服务，是 RFC 9110 描述的行为，本实验不涉及浏览器。
- 解析器是桩，不能代表真实 DNS；真实服务还要考虑解析结果在校验与使用之间变化（DNS rebinding）。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
