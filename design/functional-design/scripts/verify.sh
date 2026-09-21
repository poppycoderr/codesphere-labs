#!/usr/bin/env bash
# record 浅不可变、组合顺序、Stream 副作用、Optional 与递归记忆化
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/Fn.java >"$OUT/fn-output.txt"
write_environment "$OUT/environment.txt"
f="$OUT/fn-output.txt"
expect_line "$f" "Cart.items after caller mutates source: [book, phone]" "record 浅不可变"
expect_line "$f" "SafeCart.items after caller mutates source: [book]" "紧凑构造器复制后不受影响"
expect_line "$f" "8折再减30: 130.0  减30再8折: 136.0" "组合顺序改变结果"
expect_line "$f" "count=5 peek 执行次数=0" "count() 跳过 peek"
expect_line "$f" "20 轮 toList()：不一致 0 轮" "toList() 没有并发问题"
expect_line "$f" "值存在时 orElse 仍调用默认值方法 1 次" "orElse 提前求值"
expect_line "$f" "ConcurrentHashMap 递归 computeIfAbsent -> Recursive update" "递归 computeIfAbsent 抛异常"
# 并行流写 ArrayList 的结果与调度有关，只断言 20 轮中出错轮数大于 0
bad=$(sed -nE 's/^20 轮 forEach\(ArrayList::add\)：丢元素 ([0-9]+) 轮，抛异常 ([0-9]+) 轮$/\1 \2/p' "$f")
[ -n "$bad" ] && [ $(( ${bad% *} + ${bad#* } )) -gt 0 ] || fail "并行流写 ArrayList 没有出错：$bad"
log "通过：并行流写 ArrayList 出错（丢元素、抛异常轮数：$bad）"
