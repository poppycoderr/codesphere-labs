#!/usr/bin/env bash
# 异构执行器的调度：队头阻塞、按能力匹配、扩容后瓶颈在哪种能力或调度器、执行器崩溃与租约（离散时间模拟，标准库）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python -B src/lab.py' >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" 
cat "$f" >&2
# 输入固定、顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
load	40 个任务：android 8 个（每个 6 刻）、chrome 32 个（每个 2 刻）
head.5	只看队头，chrome 5 台、android 1 台：全部完成 48 刻，chrome 任务全部完成 44 刻、平均等待 21.0 刻，利用率 chrome 27%、android 100%
match.5	按能力匹配，chrome 5 台、android 1 台：全部完成 48 刻，chrome 任务全部完成 14 刻、平均等待 5.4 刻，利用率 chrome 27%、android 100%
match.10	按能力匹配，chrome 加到 10 台：全部完成 48 刻，chrome 任务全部完成 8 刻、平均等待 2.2 刻，利用率 chrome 13%、android 100%（android 才是瓶颈）
match.android2	按能力匹配，chrome 5 台、android 加到 2 台：全部完成 24 刻，chrome 任务全部完成 14 刻、平均等待 5.4 刻，利用率 chrome 53%、android 100%
head.10	只看队头，chrome 加到 10 台：全部完成 48 刻，chrome 任务全部完成 44 刻、平均等待 21.0 刻，利用率 chrome 13%、android 100%（被排在前面的 android 任务挡住）
dispatch.1	按能力匹配，chrome 10 台、android 4 台，调度器每刻只能派发 1 个：全部完成 41 刻，chrome 任务全部完成 41 刻、平均等待 20.0 刻，利用率 chrome 16%、android 29%
dispatch.unlimited	同样的执行器，调度器不限速：全部完成 12 刻，chrome 任务全部完成 8 刻、平均等待 2.2 刻，利用率 chrome 53%、android 100%
crash.nolease2	android 2 台、其中一台第 3 刻崩溃、没有租约：完成 39 个，丢失 1 个
crash.lease	同样崩溃、租约 10 刻：完成 40 个，丢失 0 个，全部完成 48 刻（只剩 1 台 android，比不崩溃时的 24 刻慢一倍）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
