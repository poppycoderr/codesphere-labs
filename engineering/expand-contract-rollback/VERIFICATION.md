# 验证记录：发布的可回退性

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. **原地改名**：执行 `RENAME COLUMN phone TO mobile` 后，滚动发布期间还在服务的 v1 实例 20 次全部失败（`Unknown column 'phone'`），v2 全部成功；把应用回退到 v1，20 次全部失败，要先执行反向迁移。
2. **扩展—迁移—收缩**：
   - 加列之后 v1 全部成功；v1 与双写的 v1.5 并存、v1.5 与读新列的 v2 并存，都是 20 次全部成功；回填 60 行；
   - 从 v2 回退到 v1.5、再回退到 v1，都全部成功；
   - 回退期间 v1 写入的 20 行 `mobile` 为空，重新发布 v2 前要再回填一次，之后两列完全一致；
   - 删除旧列后 v2 正常，v1 再次回退时 20 次全部失败：收缩是不可逆点。
3. **版本 × 表结构矩阵**（每格 20 次）：

| 版本 | 只有 phone | phone + mobile | 只有 mobile |
|---|---|---|---|
| v1（只读写 phone） | 全部成功 | 全部成功 | 全部失败 |
| v1.5（双写，读 phone） | 全部失败 | 全部成功 | 全部失败 |
| v2（收缩前：双写，读 mobile） | 全部失败 | 全部成功 | 全部失败 |
| v2（收缩后：只读写 mobile） | 全部失败 | 全部成功 | 全部成功 |

   「phone + mobile」这一列四个版本都能工作，这就是扩展—收缩路线要在中间停留的状态。
4. **事件兼容**：新增字段 `channel`，严格解析报 `UnrecognizedPropertyException`，宽容解析成功；把 `phone` 改名为 `mobile`，严格解析同样失败，宽容解析「成功」但 `phone=null`——数据悄悄丢了。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。MySQL 8.4.11，JDK 21 容器，Connector/J 8.0.27，Jackson 2.22.3。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/Rollback.java`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 版本是同一进程里的代码路径，不是真正的多个部署；它只验证了「某个版本在某个表结构上能否工作」，不涉及发布系统本身。
- 改名是最小的例子；拆分列、改类型、加 NOT NULL 约束等变更各有自己的兼容窗口，要单独设计。
- 大表加列、回填的耗时与锁不在本实验范围，见 MySQL 大表清理与批量导入两篇的实验。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-28 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
