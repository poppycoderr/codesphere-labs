# 验证记录：反斜杠的三层转义

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 用例 | 结果 |
|---|---|
| 把 Windows 路径里的反斜杠换成 `/` | Java 源码 `"\\\\"`；Python `r"\\"`；JS `/\\/g` 或 `new RegExp("\\\\", "g")` |
| JS `replace` 传字符串 | 只替换第一个；`replaceAll` 全部替换 |
| `a.b` 匹配 axb | 三种语言都是 true；quote / escape 之后都是 false（Node 24 支持 `RegExp.escape`） |
| 替换成用户输入的 `$5` | Java 抛 IndexOutOfBoundsException：No group 5；`Matcher.quoteReplacement` 后正常；Python、JS 原样输出 |
| JS 替换成用户输入的 `$&` | 被解释成整个匹配，结果仍是「{price}」；传函数时原样输出 |
| Python 替换成用户输入的 `\5` | PatternError：invalid group reference 5；传函数时原样输出 |
| Java 替换串是一个反斜杠 | IllegalArgumentException：character to be escaped is missing |
| Python 非原始字符串 `"\d"` | 长度 2，编译时 SyntaxWarning |
| Java 文本块里的 `\\d` | 仍需转义，匹配 42 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只覆盖标准库；不同版本对未知转义的处理不同（Python 3.12 起由 DeprecationWarning 改为 SyntaxWarning）。

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
