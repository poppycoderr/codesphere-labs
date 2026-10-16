# 验证记录：Vim 打开中文文件乱码

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 默认 `fileencodings`（ucs-bom,utf-8,default,latin1）打开 GBK 文件 | 识别为 latin1，显示 `ÖÐÎÄ²âÊÔ` |
| `fencs=ucs-bom,utf-8,gbk,latin1` 打开 GBK / UTF-8 文件 | cp936 / utf-8，都正确 |
| gbk 放在 utf-8 之前，打开 UTF-8 的「中文测试」 | 碰巧识别为 utf-8 |
| gbk 放在 utf-8 之前，打开 UTF-8 的「你好」 | 识别为 cp936，显示 `浣犲ソ` |
| 默认设置打开带 BOM 的 UTF-8 | utf-8 |
| `:e ++enc=gbk` | cp936，正确 |
| 以 gbk 打开、`set fileencoding=utf-8` 后保存 | 文件变成 UTF-8 字节 |
| 带 BOM 的文件 `set nobomb` 后保存 | BOM 被去掉 |
| 按 latin1 打开 GBK 文件、加一个字符后保存 | 原有字节不变 |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- `fileencodings` 的探测是逐个尝试转换，能否成功取决于文件内容；实验用两段文本说明这一点，不代表所有文本的结果。
- 只测试了 `encoding=utf-8`（容器的 `LANG=C.UTF-8`）。

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
