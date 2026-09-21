#!/usr/bin/env bash
# <这个实验验证什么>
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21

java src/Main.java >"$OUT/output.txt"
write_environment "$OUT/environment.txt"

expect_line "$OUT/output.txt" "<正文中的关键输出>" "<结论>"
