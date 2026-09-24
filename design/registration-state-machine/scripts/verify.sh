#!/usr/bin/env bash
# 报名状态机：散落分支与转换表的全矩阵对比、可达性、并发命令与版本号、副作用失败与待发送记录
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-build/run}"
require_java 21
mkdir -p "$OUT" build/classes; find "$OUT" -mindepth 1 ! -name README.md -delete
javac -encoding UTF-8 -d build/classes src/StateMachine.java
java -cp build/classes StateMachine >"$OUT/output.tsv" 2>"$OUT/transition-matrix.tsv"
write_environment "$OUT/environment.txt"

expect_line "$OUT/output.tsv" "matrix.size	5 个状态 × 5 个命令 = 25 个组合，合法 7 个" "转换表：25 个组合中 7 个合法"
expect_line "$OUT/output.tsv" "matrix.scattered_diffs	6 处不一致" "散落的分支与转换表有 6 处不一致"
expect_line "$OUT/output.tsv" "CHECKED_IN+CANCEL: 表 拒绝 / 分支 CANCELLED" "分支写法允许签到后取消"
expect_line "$OUT/output.tsv" "CANCELLED+PROMOTE: 表 拒绝 / 分支 CONFIRMED" "分支写法允许取消后被候补转正"
expect_line "$OUT/output.tsv" "graph.reachability	从 PENDING 不可达的状态 []；没有出口的状态 [CANCELLED, CHECKED_IN]" "全部可达，终态只有 CANCELLED 与 CHECKED_IN"
expect_regex "$OUT/output.tsv" "concurrency.naive	1000 轮 CANCEL 与 CHECK_IN 并发：两个命令都「成功」(9[0-9]{2}|1000) 轮" "不带版本号：绝大多数轮次两个命令都成功"
expect_line "$OUT/output.tsv" "concurrency.versioned	1000 轮带版本号：两个命令都成功 0 轮，另一个命令被拒绝 1000 次" "带版本号：每轮只有一个命令成功"
expect_line "$OUT/output.tsv" "最终送达 0 封" "状态先变、通知失败：重试被状态机拒绝，通知丢失"
expect_line "$OUT/output.tsv" "side_effect.outbox	状态 CONFIRMED；投递器第 3 轮送达，调用通知服务 3 次，最终送达 1 封" "待发送记录：投递器重试后送达"
log "全部通过，输出在 $OUT"
