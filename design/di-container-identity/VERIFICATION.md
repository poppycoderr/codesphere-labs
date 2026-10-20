# 验证记录：依赖注入容器的绑定身份与依赖图

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 只按 Class 注册 Source@raw 与 Source@daily | 只剩 1 个绑定（DailySource） |
| 只按 Class 检查 Source@daily | 报「循环依赖：Source → Source」 |
| 只按 Class 解析 Source@raw | 构造过程中重入 |
| 按 Key 注册与检查 | 2 个绑定，检查通过，解析 Source@raw 得到 raw |
| 绑定按 Key、检查路径只记 Class | 仍报「循环依赖：Source → Source」 |
| Source@a ↔ Source@b 真环 | 报出完整路径 |
| 菱形依赖，路径栈 | 通过 |
| 菱形依赖，只看已访问集合 | 在 Base 处误报循环 |
| 延迟边（Provider），构造器不调用 get() | 检查通过，解析通过，之后 get() 也通过 |
| 同样结构，构造器里立刻 get() | 检查通过，运行期重入 |
| 缺失依赖 | 报 `NeedsMissing → Source@absent` |
| 两处 `new TypeRef<List<String>>(){}` | 按 getClass() 不相等，按捕获的 Type 相等 |
| 两个独立的 `@Named("raw")` 实例 | equals 为 true |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 这是为对比身份模型写的教学容器，只支持构造器注入与 `@Named`，不处理字段/方法注入、作用域与并发；不代表任何生产容器的实现。
- 泛型只处理 `Provider<T>` 一层且类型参数为普通类，嵌套泛型、通配符与类型变量没有覆盖。
- 运行期重入检测用的是单线程集合，多线程并发构造同一组件时会误判，这一点在作用域那篇另行验证。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-20 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
