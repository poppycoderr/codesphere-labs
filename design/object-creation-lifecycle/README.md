# 对象创建与生命周期

对应文章：[object-creation-and-lifecycle.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/object-creation-and-lifecycle.md)。

Maven 项目（Spring Framework 7.0.9、JUnit 5.13.4），6 个测试覆盖：

- 不校验、不复制的 Builder 造出非法且可变的对象；把校验交给 `record` 紧凑构造器的 Builder 在 `build()` 时拒绝；
- 同一个静态单例在两个类加载器里各有一个实例；
- Spring 的 singleton 只在一个容器内唯一；prototype 注入 singleton 后不再变化，`ObjectProvider` 每次返回新实例；
- 浅复制共享集合，改动污染原对象；
- 保留 id 与版本的深复制在保存时覆盖了原报名；以模板新建时重置身份、版本和未发布的事件。

Builder 在编译期、构建期、运行期暴露错误的对比见 [design/api-evolution](../api-evolution/)，本实验不重复。

## 快速运行

```bash
make verify     # 需要 JDK 21、Maven；首次下载依赖
make evidence   # 重新采集 evidence/
make clean      # 清理本实验的构建产物
```
