# 验证记录：URI 模板编译成正则

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `/files/{name}.txt` 朴素编译，匹配 `/files/reportXtxt` | 匹配，name=report |
| 字面部分 quote 后 / Jersey | 不匹配；`/files/report.txt` 匹配 |
| `/orders/{id: (a|b)\d+}/{part}` 朴素取组 | id=a12，part=a（错位） |
| 命名组 / Jersey | id=a12，part=items |
| `replaceAll` 替换为 `US$5` | IndexOutOfBoundsException：No group 5 |
| quoteReplacement 后 | price=US$5 |
| 替换为 `C:\tmp` | 得到 `C:tmp`；quoteReplacement 后 `C:\tmp` |
| Jersey 比较器排序 | `/users/me`、`/users/{id}/{tab}`、`/users/{id: \d+}`、`/users/{id}`、`/{any: .*}` |
| `/users/me`、`/users/42`、`/users/abc` | 分别由 `/users/me`、`/users/{id: \d+}`、`/users/{id}` 处理 |
| 按长度降序排序 | `/users/me` 被 `/users/{id}` 抢走 |
| 「大于等于返回 -1」的比较器 | 25 对里违反反对称性 5 对（都是元素与自身） |
| Jersey 比较器 | 违反 0 对 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 模板语法只覆盖 `{name}` 与 `{name: regex}`，不含矩阵参数、编码与完整的 Jakarta REST 路由算法（根资源选择、子资源定位）。
- 反对称性检查只在 5 个模板上做，说明反例；比较器的传递性没有穷举。

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
