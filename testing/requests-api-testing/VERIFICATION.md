# 验证记录：用 requests 写接口测试

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 自己拼 `?q=a b&c&tag=x` | 服务端收到 q='a b'、c=''、tag='x' |
| `params={'q': 'a b&c', 'tag': ['x', 'y']}` | 查询串 `q=a+b%26c&tag=x&tag=y`，服务端收到正确的值 |
| `data=dict` | `application/x-www-form-urlencoded`，`sku=A1&qty=2` |
| `json=dict` | `application/json` |
| `data=json.dumps(...)` | 没有 Content-Type |
| `data` 里放嵌套结构 | 请求体 `items=sku&items=sku`，内容丢失 |
| 不设 timeout 请求 3 秒的接口 | 等了约 3 秒（默认无超时） |
| `timeout=1` | 约 1 秒后 ReadTimeout |
| 500 响应 | 不抛异常，`resp.ok=False` |
| `raise_for_status()` | HTTPError：500 Server Error |
| 不用 Session，登录后访问 /me | 401 |
| Session | 200；7 次请求 1 个 TCP 连接 |
| 不用 Session 发 7 次请求 | 7 个 TCP 连接 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 耗时取整到秒；容器负载很高时可能出现偏差。
- 连接数由服务端看到的客户端端口统计，只针对 HTTP/1.1 keep-alive。

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
