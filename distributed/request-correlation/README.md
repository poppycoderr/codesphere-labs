# 请求关联：编号、待完成表与迟到的响应

对应文章：[request-correlation.md](https://github.com/poppycoderr/codesphere/blob/master/docs/distributed/request-correlation.md)。

`src/Lab.java` 用一个可控的内存传输层模拟一条连接上的多路复用调用：客户端为每个请求分配编号、登记独立的完成对象，响应的到达顺序与时机由测试脚本逐步驱动，因此输出确定。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
