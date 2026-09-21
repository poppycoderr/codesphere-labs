#!/usr/bin/env bash
# 继承与组合的计数差异、折扣规则组合、密封类型新增子类型后的编译失败
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT" build
require_java 21
java src/Inherit.java >"$OUT/inherit.txt"
java src/Rules.java >"$OUT/rules.txt"
java src/Sealed.java >"$OUT/sealed.txt"
# 新增 BuyNGetOne 子类型但没有更新 switch，期望编译失败
if javac -d build/v2 src/v2-add-subtype/Sealed.java >"$OUT/sealed-v2-javac.txt" 2>&1; then fail "新增子类型后 switch 仍能编译"; fi
sed -i.bak "s#$PWD/##" "$OUT/sealed-v2-javac.txt" && rm -f "$OUT/sealed-v2-javac.txt.bak"
write_environment "$OUT/environment.txt"
expect_line "$OUT/inherit.txt" "继承 HashSet：addAll 3 个元素后 added = 6" "继承 HashSet 计数翻倍"
expect_line "$OUT/inherit.txt" "组合 HashSet：addAll 3 个元素后 added = 3" "组合计数正确"
expect_line "$OUT/rules.txt" "addAll 声明在：java.util.AbstractCollection" "HashSet.addAll 继承自 AbstractCollection"
expect_line "$OUT/rules.txt" "500 元打 8 折封顶减 30：470" "折扣规则组合"
expect_line "$OUT/sealed-v2-javac.txt" "the switch expression does not cover all possible input values" "漏处理的新子类型在编译期报错"
