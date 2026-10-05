# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `output.tsv` | 字段偏移（`layout.*`）与各场景每次操作的纳秒数；两线程场景是 5 轮的中位数 |
| `layout-default.tsv` | 不加 `-XX:-RestrictContended` 时的字段偏移 |
| `cache-line.txt` | 容器内 `getconf` 报告的缓存行大小与 `@Contended` 相关的 JVM 默认参数 |
| `stderr.log` | JVM 的标准错误（`sun.misc.Unsafe::objectFieldOffset` 的弃用警告） |
| `environment.txt` | 运行环境、JDK 镜像 digest、宿主机 CPU 型号与宿主机报告的缓存行大小 |
