#!/usr/bin/env bash
# 调试工具对程序与证据的影响：控制台引用、序列化快照、条件断点副作用、暂停改变时间、重放请求、覆盖率（Playwright 1.63.0 + CDP）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Playwright Python 1.63.0 镜像（Chromium 153）；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="mcr.microsoft.com/playwright/python:v1.63.0-noble@sha256:72bd171a9ffc2b4b59532aaa6210e21014d07093120dc25528870c0b840da1f0"
f="$OUT/output.tsv"
mkdir -p "$LABS_CACHE/pip"
docker run --rm --cpus=2 --memory=2g -v "$PWD:/w" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$IMG" sh -c 'pip install -q --disable-pip-version-check --root-user-action=ignore --break-system-packages -r requirements.txt >/dev/null 2>&1 && python3 -B src/lab.py' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
console.ref	console.log(cart) 之后代码把 total 改成 4、items 加了一项：日志里折叠的预览 total=3（打印那一刻的摘要）；展开对象读到 total=4、items 有 4 项（展开时的当前值）
console.snapshot	用 JSON.stringify 留快照：{a: undefined, f() {}, n: NaN, d: Date} 变成 {"n":null,"d":"1970-01-01T00:00:00.000Z"}；BigInt 抛 TypeError，循环引用抛 TypeError
bp.condition	在 checkout 里设条件断点，条件写成 items.pop(), false（想「看一下」又不想暂停）：断点一次也没暂停=True；两次 checkout 依次返回 2、1，购物车剩 1 项
bp.timing	slowStep 里超过 500ms 就走降级分支：不调试时返回「正常」；在中间行停 1 秒后返回「超时降级」，暂停 1 次
replay	页面下单一次后在网络面板重放这个 POST（Network.replayXHR，即网络面板的 Replay XHR）：服务端收到下单 2 次
coverage	加载与前面几个场景之后，handleExport 执行 0 次（覆盖率工具标为未执行）；点击「导出」后 1 次；neverCalled 两次都是 0 次，但它可能只在别的路径上被调用
env	chromium 153.0.8010.12
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
