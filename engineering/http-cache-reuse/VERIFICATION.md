# 验证记录：浏览器的 HTTP 缓存

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 源站的响应头 | 连续 fetch 两次，源站收到 | 第二次的验证头 |
|---|---|---|
| `Cache-Control: max-age=600` | 1 次 | |
| 没有任何缓存相关的头 | 2 次 | |
| 只有 `Last-Modified`（10 天前） | 1 次 | |
| `Cache-Control: no-store` | 2 次 | |
| `Cache-Control: no-cache` 与 `ETag` | 2 次 | `If-None-Match: "v1"`，源站回 304，页面仍拿到完整内容 |

- `max-age=600` 加 `Age: 599`：取一次、等 2 秒再取，源站收到 2 次。
- `no-cache` 加 `ETag`，内容在第二次与第三次之间换了版本：三次拿到 release 1、release 1、release 2；源站收到 3 次，其中 2 次带验证头。
- 普通刷新页面：两个子资源都是 `max-age=600`，一个带 `immutable`、一个不带，都没有回源。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 只验证了 Chromium 153 的无头模式。启发式新鲜度的具体时长、刷新时对子资源的处理，各浏览器的实现不同。
- 用的是页面里的 `fetch`（默认缓存模式）与普通刷新，没有覆盖强制刷新、开发者工具里的「禁用缓存」、Service Worker。
- 没有共享缓存，`private`、`s-maxage`、`Vary` 在共享缓存里的作用没有验证。
- 没有测量启发式新鲜度维持多久，只验证了它让第二次请求没有回源。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-16 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
