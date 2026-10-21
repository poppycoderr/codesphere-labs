# 折线编辑会话：预览、提交、撤销与外部更新

对应文章：[polyline-edit-session.md](https://github.com/poppycoderr/codesphere/blob/master/docs/design/polyline-edit-session.md)。

一个原生 Canvas 的折线编辑器，纯模型与历史记录（`src/polyline.js`）和画布适配层（`src/editor.js`）分开。用 Playwright 驱动真实 Chromium，在桌面宽度 DPR 1 与手机宽度 DPR 2 两种配置下拖动顶点，检查预览与提交、取消、撤销重做、拖出画布、外部更新使基准版本失效，并对比快照式与命令式撤销。

## 快速运行

```bash
make verify     # 在固定 digest 的 Playwright Python 1.63.0 镜像里运行，逐行比较输出；截图只用于人工检查
make evidence
make clean
```

结果与限制见 [VERIFICATION.md](VERIFICATION.md)。
