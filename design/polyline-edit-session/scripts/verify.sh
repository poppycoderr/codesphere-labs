#!/usr/bin/env bash
# 折线编辑会话：真实 Chromium 中的预览与提交、取消、撤销重做、拖出画布、外部更新导致基准版本失效（Playwright 1.63.0）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Playwright Python 1.63.0 镜像，页面由容器内的本地 HTTP 服务提供；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMAGE="mcr.microsoft.com/playwright/python:v1.63.0-noble@sha256:72bd171a9ffc2b4b59532aaa6210e21014d07093120dc25528870c0b840da1f0"
f="$OUT/output.tsv"
docker run --rm --cpus=2 --memory=2g -v "$PWD:/w" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$IMAGE" sh -c \
  'pip install -q --disable-pip-version-check --root-user-action=ignore --break-system-packages -r requirements.txt >/dev/null 2>&1 && python3 -B src/browser.py "$1"' sh "$OUT" >"$f"
write_environment "$OUT/environment.txt" "image: $IMAGE" "playwright: 1.63.0"
cat "$f" >&2
# 鼠标轨迹与页面固定，输出是确定的：与预期逐行比较（截图只用于人工检查，不参与断言）
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	chromium=153.0.8010.12
900px.dpr1.canvas	画布显示宽度 600px，像素缓冲宽度 600；初始 p1(80,80) p2(240,160) p3(430,100)，版本 0
900px.dpr1.preview	拖动中：预览 p1(120,110)，正式模型 p1(80,80)，版本 0
900px.dpr1.commit	松开：committed，正式模型 p1(120,110)，版本 1，撤销栈 1
900px.dpr1.undo	撤销：p1(80,80)，版本 2，重做栈 1
900px.dpr1.redo	重做：p1(120,110)，版本 3
900px.dpr1.escape	拖动中按 Esc：cancelled，正式模型 p1(120,110)，版本 3
900px.dpr1.pointercancel	拖动中派发 pointercancel：cancelled，正式模型 p1(120,110)
900px.dpr1.outside	拖到画布左侧之外再松开：committed，p1(0,140)（x 限制在 0）
900px.dpr1.stale	拖动 p2 期间别处把 p3 改到 y=40：松开时 stale，结果 p1(0,140) p2(240,160) p3(430,40)
900px.dpr1.undo_external	随后撤销一次：p1(120,110) p2(240,160) p3(430,100)（快照式撤销把别处对 p3 的修改也撤掉了）
900px.dpr1.branch	撤销后重做栈 1 条，再提交一次新拖动：committed，重做栈 0 条；顶点 ID ['p1', 'p2', 'p3']
900px.dpr1.command_undo	命令式撤销：提交 p1 的拖动、别处改 p3 之后撤销：applied，p1(80,80) p2(240,160) p3(430,40)
900px.dpr1.command_conflict	命令式撤销：提交 p1 的拖动、别处又改了 p1 之后撤销：conflict，p1(300,100)（不覆盖别人的修改）
900px.dpr1.errors	页面错误 0 个
390px.dpr2.canvas	画布显示宽度 358px，像素缓冲宽度 1200；初始 p1(80,80) p2(240,160) p3(430,100)，版本 0
390px.dpr2.preview	拖动中：预览 p1(120,110)，正式模型 p1(80,80)，版本 0
390px.dpr2.commit	松开：committed，正式模型 p1(120,110)，版本 1，撤销栈 1
390px.dpr2.undo	撤销：p1(80,80)，版本 2，重做栈 1
390px.dpr2.redo	重做：p1(120,110)，版本 3
390px.dpr2.escape	拖动中按 Esc：cancelled，正式模型 p1(120,110)，版本 3
390px.dpr2.pointercancel	拖动中派发 pointercancel：cancelled，正式模型 p1(120,110)
390px.dpr2.outside	拖到画布左侧之外再松开：committed，p1(0,140)（x 限制在 0）
390px.dpr2.stale	拖动 p2 期间别处把 p3 改到 y=40：松开时 stale，结果 p1(0,140) p2(240,160) p3(430,40)
390px.dpr2.undo_external	随后撤销一次：p1(120,110) p2(240,160) p3(430,100)（快照式撤销把别处对 p3 的修改也撤掉了）
390px.dpr2.branch	撤销后重做栈 1 条，再提交一次新拖动：committed，重做栈 0 条；顶点 ID ['p1', 'p2', 'p3']
390px.dpr2.command_undo	命令式撤销：提交 p1 的拖动、别处改 p3 之后撤销：applied，p1(80,80) p2(240,160) p3(430,40)
390px.dpr2.command_conflict	命令式撤销：提交 p1 的拖动、别处又改了 p1 之后撤销：conflict，p1(300,100)（不覆盖别人的修改）
390px.dpr2.errors	页面错误 0 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
