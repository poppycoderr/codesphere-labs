#!/usr/bin/env bash
# 三级调用链上的重试放大与超时倒挂：每层重试、只在入口重试、按预算重试；慢下游时是否传递截止时间
# 约 45 秒；请求计数是确定的，同时处理的最大数只断言下限
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/RetryChain.java >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"
f="$OUT/output.tsv"
expect_line "$f" "100 个用户请求：用户成功 0 个，C 收到 2700 个请求（每个用户请求 27.0 个）" "每层重试 3 次：C 收到 27 倍请求"
expect_line "$f" "C 收到 300 个请求（每个用户请求 3.0 个）" "只在入口重试：3 倍"
expect_line "$f" "C 收到 133 个请求（每个用户请求 1.3 个）" "每层按 10% 预算重试：约 1.3 倍"
expect_line "$f" "共尝试 1 次：用户在 10" "超时倒挂：用户 1 秒放弃"
expect_regex "$f" "slow\.inverted	.*C 收到 1 个请求，做完 1 次（用户都已放弃）" "超时倒挂：用户放弃后 C 仍做完了这次处理"
expect_regex "$f" "slow\.deadline	.*C 收到 1 个请求，做完 0 次（用户都已放弃），因截止时间不够而拒绝 1 次" "传递截止时间：C 发现时间不够，直接拒绝"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"slow\.deadline\t.*用户在 (\d+) ms 时放弃", t)
assert int(m[1]) < 500, m[0]
print(f"通过：传递截止时间时用户 {m[1]} ms 就拿到失败，不用等满 1 秒")
m = re.search(r"slow\.inverted-retries\t.*用户在 (\d+) ms 时放弃；C 收到 (\d+) 个请求，做完 (\d+) 次.*同时处理最多 (\d+) 个", t)
user, got, done, peak = map(int, m.groups())
assert 3000 <= user < 3500 and got == 9 and done == 9 and peak >= 3, m[0]
print(f"通过：倒挂加每层重试，用户 {user} ms 放弃，C 仍做完 {done} 次，同时最多 {peak} 个")
PY
log "全部通过，输出在 $OUT"
