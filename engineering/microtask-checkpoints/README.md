# 微任务什么时候执行

对应文章：[microtask-checkpoints.md](https://github.com/poppycoderr/codesphere/blob/master/docs/web/microtask-checkpoints.md)。

`src/node.mjs` 在 Node 24.21.0 中验证各队列的顺序（含 CommonJS 对照 `src/order.cjs`）、Promise 解决过程多出来的两拍、状态锁定、await 与定时器、微任务排队推迟定时器；`src/browser.py` 用 Playwright 在 Chromium 中打开 `src/page.html`，对比真实点击与脚本调用 click() 时两个监听器之间是否清空微任务。

```bash
make verify     # 需要 Docker；约 30 秒
make evidence   # 重新采集 evidence/
make clean
```
