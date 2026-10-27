# 验证记录：请求结果归谁

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| 404/500 的 HTML 页面直接 r.json() | SyntaxError；检查 r.ok 后按失败处理 |
| 404 的 JSON 错误体直接 r.json() | 被当成成功数据 |
| 204 直接 r.json() | SyntaxError |
| 连不上服务端 | fetch 才 reject（TypeError） |
| then(onOk).catch(onFail)，onOk 抛错 | onFail 也被调用 |
| 先搜 a（300ms）再搜 ab（50ms），按到达顺序显示 | 最终显示 a |
| 代际标记 / AbortController | 最终显示 ab；服务端都完成了两个搜索 |
| AbortSignal.timeout 与 abort() | TimeoutError 与 AbortError |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 使用 Node 内置的 fetch（undici）；浏览器 fetch 的错误语义相同，但网络错误的细节（cause）不同。
- 延迟由服务端 sleep 构造，结论只依赖「先发的请求后到」，不测量网络时间。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-27 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
