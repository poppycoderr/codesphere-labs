# 拦截链的三条契约

对应文章：[interceptor-chain-contracts.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/interceptor-chain-contracts.md)。

`src/Lab.java` 用一组最小的检查器、信号量与候选过滤器验证拦截链的三条契约：任一检查失败都要停止；只释放自己拿到的许可；链与候选列表在请求之间不共享可变状态。另含「首个匹配」规则的遮蔽。

```bash
make verify     # 需要 Docker；约 10 秒
make evidence   # 重新采集 evidence/
make clean
```
