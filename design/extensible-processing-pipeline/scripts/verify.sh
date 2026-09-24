#!/usr/bin/env bash
# 可扩展流程：策略注册的重复与缺失、回调换线程后丢失上下文与异常包装、责任链的顺序与节点副作用
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-build/run}"
require_java 21
mkdir -p "$OUT" build/classes; find "$OUT" -mindepth 1 ! -name README.md -delete
javac -encoding UTF-8 -d build/classes src/Pipeline.java
java -cp build/classes Pipeline >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"

expect_line "$OUT/output.tsv" "strategy.first_match	20 个座位的工作坊：注册顺序 A 容量 20，顺序反过来容量 22" "第一个匹配的策略胜出：结果取决于注册顺序"
expect_line "$OUT/output.tsv" "strategy.strict_duplicate	重复的容量规则 WORKSHOP" "启动时索引发现重复"
expect_line "$OUT/output.tsv" "strategy.strict_missing	缺少容量规则 [WEBINAR]" "启动时索引发现缺失"
expect_line "$OUT/output.tsv" "callback.inline	activity-1 的租户：tenant-a" "同线程回调看得到租户"
expect_line "$OUT/output.tsv" "callback.async	activity-1 的租户：null" "线程池中的回调丢失租户"
expect_line "$OUT/output.tsv" "callback.async_exception	调用方收到 CompletionException，原因 IllegalStateException" "异步回调的异常被包装"
expect_line "$OUT/output.tsv" "chain.order.auth,rate-limit	合法请求通过 40 / 40" "先鉴权：合法请求全部通过"
expect_line "$OUT/output.tsv" "chain.order.rate-limit,auth	合法请求通过 20 / 40，拒绝原因 {未登录=30, 超过限流=50}" "先限流：未登录请求耗掉配额"
expect_line "$OUT/output.tsv" "→ 拒绝：重复请求" "幂等节点在校验之前：修正后的重试被当成重复"
expect_line "$OUT/output.tsv" "[auth=pass, validation=pass, idempotency=pass] → 通过" "幂等节点在校验之后：重试通过"
log "全部通过，输出在 $OUT"
