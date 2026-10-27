# 包一层要透传什么

对应文章：[wrapper-transparency.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/wrapper-transparency.md)。

`src/run.mjs` 验证包装函数（this、返回值、异常、Promise、顺序）、Proxy（get/set 陷阱、接收者、不变量）与缓存代理的键；`src/ts/` 里同一个类分别用标准装饰器与 experimentalDecorators 写法，由 `src/run.sh` 用 TypeScript 7.0.2 以两种模式编译并运行。

```bash
make verify     # 需要 Docker；约 20 秒
make evidence   # 重新采集 evidence/
make clean
```
