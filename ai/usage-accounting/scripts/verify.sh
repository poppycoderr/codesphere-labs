#!/usr/bin/env bash
# 用量记账：片段与 token、断开、重试、回退、回调重复注册、重复投递与幂等入账（Python 3.14.8）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -w /w "$IMG" python src/lab.py 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
chunks	流式返回 12 个文本片段、43 个字符；最后的 usage 事件：输入 120、输出 31 个 token
cancel	收到 5 个片段后客户端断开：应用收到的 usage 事件 0 个；供应商记账输出 12 个 token
naive.retry	第一次尝试输出 6 个片段后超时、重试成功：账本 2 条、输出 62 个 token（两条都是第二次尝试的 usage，回调注册了两次；第一次没有入账）；供应商实际计费 46（第一次 15 + 第二次 31）
naive.redeliver	usage 事件被重复投递一次：账本 2 条、输出 62；供应商计费 31
naive.fallback	大模型输出 3 个片段后超时、回退到小模型成功：账本 [('big', 31), ('small', 31)]，按记录算费用 0.341；按供应商账单算 0.091
good.retry	按响应 ID 幂等入账、断开或超时的响应事后对账（1 条）：账本 [(1, 'big', 15), (2, 'big', 31)]，输出合计 46、费用 0.460；供应商计费 46、费用 0.460
good.redeliver	按响应 ID 幂等入账、断开或超时的响应事后对账（0 条）：账本 [(1, 'big', 31)]，输出合计 31、费用 0.310；供应商计费 31、费用 0.310
good.fallback	按响应 ID 幂等入账、断开或超时的响应事后对账（1 条）：账本 [(1, 'big', 6), (2, 'small', 31)]，输出合计 37、费用 0.091；供应商计费 37、费用 0.091
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
