# 验证记录：同一个正则在 Java、Python、JavaScript 里

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 用例 | Java | Python | JavaScript |
|---|---|---|---|
| `^\d+$` 匹配阿拉伯-印度数字、全角数字 | false | True | false |
| `^\w+$` 匹配「中文」 | false | True | false |
| `abc$` 匹配 "abc\n" | true | True | false |
| `\d{3}` 对 "1234" 的整串匹配 | `matches` false / `find` true | `match` True / `fullmatch` False | `test` true |
| `(?<y>…)` 命名分组 | 支持 | 报错，要写 `(?P<y>…)` | 支持 |
| 替换模板 `$2/$1` | 10/2026 | 原样输出 $2/$1 | 10/2026 |
| 替换模板 `\2/\1` | 2/1 | 10/2026 | 原样输出 |
| `"a,b,,".split(",")` | [a, b] | ['a', 'b', '', ''] | ["a","b","",""] |
| 按 `.` 切分 "a.b.c" | [] | 6 个空串 | 6 个空串 |
| 忽略大小写匹配 é 与 É | false（加 UNICODE_CASE 后 true） | True | true |
| JS 带 g 的正则对 4 个数字反复 test | | | [true,false,true,false] |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖各语言标准库的正则引擎（`java.util.regex`、`re`、ECMAScript RegExp），不包括 Python 的第三方 `regex` 模块。
- JavaScript 的 `u`、`v` 标志与 Java 的 `UNICODE_CHARACTER_CLASS` 会改变部分结果，实验只演示了 Java 的一个对照。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-17 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
