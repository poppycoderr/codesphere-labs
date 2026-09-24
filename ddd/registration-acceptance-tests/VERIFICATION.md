# 验证记录：事件风暴产物进入验收测试

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

1. 同一组 12 个验收用例检查初稿模型 `DraftSession`：失败 3 个，全部是 R3（重复报名）的用例，其余 9 个通过。缺失的规则会以具体用例失败的形式暴露，而不是等到线上出现一个人占两个名额。
2. 同一组用例检查修订模型 `Session`：12 个全部通过。
3. 规则表 8 条规则都至少有 1 个用例（R3 有 3 个，R5、R8 各 2 个），12 个用例引用的编号都在规则表中。
4. 词汇表 8 个术语的代码名都能在 `src/main` 中找到对应的类型或方法。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。组件版本：jdk 21、junit 5.13.4。

## 三、执行步骤

1. `mvn test -Dmodel=draft`：用例通过系统属性选择初稿模型，预期失败，保存汇总；
2. `mvn test`：修订模型，预期全部通过；
3. `scripts/trace.py` 读取修订模型的用例汇总、`model/rules.md` 与 `model/glossary.md`，输出可追踪表。

surefire 配置为在报告中使用 `@DisplayName`，规则编号因此进入 `test-results*.txt`。

## 四、证据

| 文件 | 内容 |
|---|---|
| `evidence/test-results-draft.txt` | 初稿模型的用例结果 |
| `evidence/test-results.txt` | 修订模型的用例结果 |
| `evidence/traceability.tsv` | 规则—用例、术语—代码的对应 |
| `evidence/maven-test-draft.log`、`maven-test.log` | Maven 输出，含初稿模型的断言失败信息 |
| `evidence/environment.txt` | 运行环境 |

上表中的文件由 `make evidence` 生成，未手工修改；断言内容见 `scripts/verify.sh`。

## 五、误差、限制与不能推出的结论

- 讨论记录、热点与规则是为本实验编写的虚构案例，不是真实工作坊的统计结果。
- 可追踪检查只证明「每条规则至少有一个用例」「每个术语在代码中有名字」，不证明用例充分，也不证明代码里的含义与术语一致。
- 未决热点 H3（递补是否需要候补者确认）按直接递补实现；它是业务假设，测试只证明代码与这条假设一致。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-09-24 | 首次建立，全部断言通过 | 新文章，数字取自本次证据 |
