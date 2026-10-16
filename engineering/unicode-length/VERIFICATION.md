# 验证记录：一个字符串有多长：字节、码点、UTF-16 与字形

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 字符串 | Java `length()` / JS `length` | Python `len()` | UTF-8 字节 | 字形 |
|---|---|---|---|---|
| 中 | 1 | 1 | 3 | 1 |
| é（预组合） | 1 | 1 | 2 | 1 |
| e + 组合重音 | 2 | 2 | 3 | 1 |
| 笑脸 | 2 | 1 | 4 | 1 |
| 一家三口 | 8 | 5 | 18 | 1 |
| 中国国旗 | 4 | 2 | 8 | 1 |
| 带肤色的点赞 | 4 | 2 | 8 | 1 |

| 用例 | 结果 |
|---|---|
| Java `substring(0, 1)` / JS `slice(0, 1)` 截笑脸 | 孤立代理 U+D83D；Java 编码成 UTF-8 变成 "?"，JS 的 TextEncoder 编码成 U+FFFD |
| Python `s[:1]` 截一家三口 | 只剩第一个人 U+1F468 |
| 三种语言按码点反转国旗 | 区域指示符变成 N、C 的顺序（新喀里多尼亚） |
| é 预组合与分解形式比较 | 不相等；NFC 规范化后相等 |
| "straße" 转大写 | STRASSE，长度 6 → 7 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 字形分割依赖各运行时内置的 Unicode 版本（JDK 25、Node 24 的 ICU）；更新的表情序列在旧版本上可能被分成多个字形。
- Python 的字形分割需要第三方库（如 `regex` 的 `\X`），实验只使用标准库。

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
