# 渐进发布规则

对应文章：[designing-reusable-server-components.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/designing-reusable-server-components.md)。

单文件 `src/Rollout.java`，一个本地规则引擎：

- 以 `SHA-256(开关名:用户)` 取 0—9999 的桶，比较比例、放量单调性、重新加载后的稳定性；与按随机数决定、不以开关名加盐两种写法对比；
- 规则按优先级从高到低匹配，加载时拒绝「优先级相同、条件重叠、结果相反」的规则；
- 非法比例被拒绝并保留旧版本；以旧版本规则发布新版本实现回滚；
- 两个节点配置不同步期间，同一用户在两个节点之间得到不同结果的次数。

## 快速运行

```bash
make verify     # 需要 JDK 21
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物
```
