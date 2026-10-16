# 不变量的入口

对应文章：[invariant-entry-points.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/invariant-entry-points.md)。

`src/InvariantLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，依赖 Jackson 3.2.3。三条不变量：数量在 1 到 99 之间；一张购物车最多 3 行、每行数量至少 1；订单只能按「新建 → 已支付 → 已发货」转换。每条不变量都在构造器或业务方法里检查，实验把对象放到其他创建与修改入口前：JSON 反序列化（带无参构造器的类、record、只有 `@JsonCreator` 构造器的类）、Java 序列化（改动字节后反序列化，有无 `readObject` 校验）、反射写私有字段、直接返回内部列表、只读视图里的可变元素、父类的公共 setter；最后比较 `Long` 类型 id 的 `==` 与 `equals`。

## 快速运行

```bash
make verify     # 需要 Docker 与访问 Maven Central；约 20 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
