#!/usr/bin/env bash
# 设计原则与设计模式两篇文章引用的 JDK 事实：Connection 接口规模、default 方法行为、只读视图与不可变集合、装饰器与代理的栈深度
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/Facts.java >"$OUT/facts.txt"
java src/Defaults.java >"$OUT/connection-defaults.txt"
java src/Frames.java >"$OUT/stack-frames.txt"
write_environment "$OUT/environment.txt"
expect_line "$OUT/facts.txt" "java.sql.Connection 公共方法 60 个，其中抽象 54，default 6" "Connection 60 个方法，54 抽象、6 default"
expect_line "$OUT/facts.txt" "修改底层列表后：unmodifiableList = [a, b, c]，List.copyOf = [a, b]" "只读视图随底层列表变化，copyOf 不变"
expect_line "$OUT/connection-defaults.txt" "setShardingKey(null)：SQLFeatureNotSupportedException" "setShardingKey 默认抛 SQLFeatureNotSupportedException"
expect_line "$OUT/stack-frames.txt" "直接调用：抛出点到调用方之间 1" "直接调用 1 帧"
expect_line "$OUT/stack-frames.txt" "三层装饰器：抛出点到调用方之间 4" "三层装饰器 4 帧"
expect_line "$OUT/stack-frames.txt" "JDK 动态代理：抛出点到调用方之间 5" "JDK 动态代理 5 帧"
