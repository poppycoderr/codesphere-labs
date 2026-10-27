#!/usr/bin/env bash
# 微任务检查点：Node 的队列顺序与 Promise 解决过程，Chromium 中真实点击与脚本 click() 的差别（Node 24.21.0、Playwright 1.63.0）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 node:24.21.0-slim 与 Playwright Python 1.63.0 镜像；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="node:24.21.0-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
f="$OUT/output.tsv"
PW="mcr.microsoft.com/playwright/python:v1.63.0-noble@sha256:72bd171a9ffc2b4b59532aaa6210e21014d07093120dc25528870c0b840da1f0"
mkdir -p "$LABS_CACHE/pip"
{ docker run --rm -v "$PWD:/w" -w /w "$IMG" node src/node.mjs; docker run --rm --cpus=2 --memory=2g -v "$PWD:/w" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PW" sh -c 'pip install -q --disable-pip-version-check --root-user-action=ignore --break-system-packages -r requirements.txt >/dev/null 2>&1 && python3 -B src/browser.py'; } 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
node.order.esm	ES 模块顶层：同步代码 → Promise.then → queueMicrotask → nextTick → setTimeout
node.adopt	B：resolve(值) → C：第 1 拍 → C：第 2 拍 → A：resolve(另一个 Promise) → C：第 3 拍
node.adopt-then	对照 1 → then 回调返回值 → 对照 2 → 对照 3 → then 回调返回 Promise → 对照 4
node.locked	外层先 resolve(一个还在等待的 Promise)，随后 reject(超时)：reject 之后外层仍是「待定」，内层完成后外层「兑现：库存 3」
node.await	开始等待 → await 之后的同步代码先跑 → 别的定时器 → 等待结束
node.starve	setTimeout 执行时，微任务已经跑了 10000 个
node.order.cjs	CommonJS 主模块：同步代码 → nextTick → Promise.then → queueMicrotask → setTimeout
node	v24.21.0
browser.version	chromium=153.0.8010.12
browser.user-click	用户点击（真实输入事件）：监听器 1 → 监听器 1 的微任务 → 监听器 2
browser.script-click	脚本里调用 btn.click()：监听器 1 → 监听器 2 → 监听器 1 的微任务
browser.order	同步代码 → Promise.then → queueMicrotask → setTimeout
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
