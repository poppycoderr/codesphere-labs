# 验证记录：保存时格式化，谁说了算

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 两个服务都能格式化，不过滤 | 依次执行，后一个覆盖前一个：缩进 4/8 |
| 按名字 / filter | 只用 fmt2：2/4；只用 fmt4：4/8 |
| 同步格式化超时 | 不报错只提示 timeout，缓冲区不变；迟到的结果被丢弃 |
| 异步格式化期间用户修改 | 修改被整篇替换覆盖 |
| 异步格式化期间不修改 | 格式化生效 |
| BufWritePre 同步 / 异步 | 磁盘为格式化后 / 格式化前的内容；异步时缓冲区随后变成未保存 |
| 旧接口 | resolved_capabilities、vim.lsp.buf.formatting 都是 nil |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 格式化服务返回整篇替换的编辑；真实服务多返回局部编辑，过期的局部编辑会落在错误的位置，而不是整篇覆盖。
- 服务按消息顺序单线程处理，格式化请求期间到达的 didChange 要等它处理完才生效；多线程的服务可能在计算时已经看到新内容。
- 只覆盖 Neovim 内置 LSP 客户端；conform.nvim 等格式化插件有自己的版本检查与合并策略。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-30 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
