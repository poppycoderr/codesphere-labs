# 短链服务的四个契约问题

对应文章：[short-url-service.md](https://github.com/poppycoderr/codesphere/blob/master/docs/system-design/short-url-service.md)。

单文件程序 `src/ShortUrl.java`，连接共享的 MySQL 8.4.11，在 JDK 21 容器里运行（容器内有 curl）。不访问任何外网目标，所有 URL 使用 `example.com` 等演示域名：

1. 短码生成：自增 ID 转 Base62 的连续性；随机 7 位 Base62 在 100 万、1,000 万规模下的重复数；SHA-256 截取 32 位在 10 万、100 万、1,000 万个不同 URL 下的碰撞数，与生日界估计对照；
2. 20 个并发请求创建同一个长 URL：「先按哈希查、没有再插入」（表上无唯一约束）与「直接插入、唯一约束冲突后读出已有短码」；
3. 进程内的跳转服务分别返回 301、302、307、308，用 JDK HttpClient 与 curl 以 POST 请求短链，记录目标地址收到的方法与请求体长度；
4. 目标地址校验：协议白名单、拒绝 userinfo、按解析结果拒绝回环、内网、链路本地地址；解析器是演示用的桩，不查真实 DNS。

## 快速运行

```bash
make verify     # 需要 Docker（Compose v2）、Python 3；约 30 秒（不含拉取镜像）
make evidence
make clean
```

镜像固定 digest，容器不暴露宿主机端口，演示密码为 `example_password`，只在本地容器中使用。
