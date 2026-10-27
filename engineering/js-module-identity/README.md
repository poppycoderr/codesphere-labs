# 模块是谁

对应文章：[js-module-identity.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/js-module-identity.md)。

`src/run.mjs` 在临时目录里写出几组夹具（循环依赖的 CommonJS 与 ESM 模块、导出可变变量的模块、嵌套安装两份的包、按 import/require 条件导出的包），每组在独立的子进程中运行并记录结果。

```bash
make verify     # 需要 Docker；约 5 秒
make evidence   # 重新采集 evidence/
make clean
```
