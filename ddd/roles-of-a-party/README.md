# 同一个人的几种角色

对应文章：[roles-of-a-party.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ddd/roles-of-a-party.md)。

`src/RoleLab.java` 在固定 digest 的 temurin 25 容器里以源码方式运行，所有输入写在源码里。一个平台用户可以是学员、讲师，三种写法：一个大实体 `Member` 加布尔标志与只对某个角色有意义的字段；一个类 `Person` 同时实现 `Learner` 与 `Instructor` 接口；参与方 `Party` 加角色对象（sealed 接口 `Role`，每个角色有自己的有效期和字段）。

## 快速运行

```bash
make verify     # 需要 Docker；约 10 秒
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
