#!/usr/bin/env bash
# 等待人工澄清的代理：持久状态、重启恢复、错题与重复投递、过期、取消、提问预算，对比自问自答（Python 3.14、SQLite）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm --network=none -v "$PWD/src:/src:ro" "$PY" python /src/lab.py >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
# 时钟由参数注入，输入固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
self_answer	模型自问自答：直接产出 2 条验收条件，其中 2 条的依据是模型自己编的答案（q-late, q-group）
start	新任务：awaiting_input(q-late)
restart	重启后读取持久状态：awaiting_input，仍在等 q-late，没有重新提问（已提问 1 次）
wrong_question	回答一个没有在问的问题：拒绝：在等 q-late，收到的是 q-group
answer1	回答 q-late：接受，awaiting_input(q-group)
duplicate	同一条回答因网络重试再投递一次：重复投递，忽略
answer2	回答 q-group：接受，done
draft	草案依据的都是人给出的答案：q-group: 组内任一成员提交，以最后一次为准；q-late: 超过 24 小时记为迟交，不影响继续提交
after_done	任务完成后又收到一条回答：拒绝：当前状态是 done
expire.start	限时 10 秒的任务：awaiting_input(q-late)
expire.late	第 11 秒才收到回答：拒绝：已过期，任务转为 expired
cancel	等待中取消：已取消；再投递回答：拒绝：当前状态是 cancelled
budget	只允许问 1 个问题：回答之后 needs_human_review，不再继续追问
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
