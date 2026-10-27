#!/usr/bin/env bash
# 迭代器的 return()、生成器 finally、异步迭代与流的提前结束（Node 24.21.0）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 node:24.21.0-slim 容器；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="node:24.21.0-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$IMG" node src/run.mjs 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
exit.break	for...of 中 break：打开连接 → return：关闭连接
exit.throw	循环体抛错：打开连接 → return：关闭连接
exit.done	正常读完：打开连接 → 读完，关闭连接（不调用 return）
destructure	const [first] = cursor 只取一个：打开连接 → return：关闭连接；展开 [...cursor] 读了 3 页：打开连接 → 读完，关闭连接
no-return	手写游标没有 return，break 之后：打开连接（连接没有关闭）
generator	生成器里用 try/finally 释放：break 时 打开连接 → finally：关闭连接；手动调用两次 next 后丢弃 打开连接（finally 永远不会执行）；手动调用 return() 打开连接 → finally：关闭连接
async	for await 中 break：打开游标 → finally：关闭游标；可读流在 for await 中 break 后 destroyed=true
array-like	{ length: 2, 0: 'a', 1: 'b' } 用 for...of：TypeError：arrayLike is not iterable；Array.from 得到 [a,b]
mutate	for...of 遍历数组时往里 push：访问了 [任务1,任务2,追加1,追加2,追加3]（数组迭代器每一步都重新读 length，新加的元素也会被访问）
node	v24.21.0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
