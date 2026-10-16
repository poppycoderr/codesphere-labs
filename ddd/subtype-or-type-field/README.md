# 子类型还是类型字段

对应文章：[subtype-or-type-field.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/subtype-or-type-field.md)。

`src/SubtypeLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，连接同一 compose 项目里的 PostgreSQL 18。场景是工时登记的目标：客户项目、内部项目，以及后来加入的休假。五组：用字符串类型字段加带 default 的分支、新增类型值后不改分支；sealed 接口加没有 default 的 switch 表达式，在进程内用 `javax.tools.JavaCompiler` 编译新增子类型前后的源码；为了复用而继承、子类拒绝父类操作；租户默认值与项目覆盖值的复制和动态继承；子类型分表时的身份分配与引用完整性。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（不含拉取镜像）
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
