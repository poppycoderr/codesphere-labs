#!/usr/bin/env bash
# 实体与值对象：身份相等与值相等、BigDecimal 的 scale、构造即校验、record 的浅不可变、保存时才分配标识的问题、类型化标识
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "junit: 5.13.4"
normalize_paths <"$OUT/maven-test.log" >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=7 failures=0" "7 个测试全部通过"
expect_line "$OUT/facts.tsv" "同一标识、手机号不同：equals=true；不同标识、属性完全相同：equals=false" "实体按标识相等"
expect_line "$OUT/facts.tsv" "Money(100) 与 Money(100.00)：equals=true；a.plus(0.5) 后 a=100.00，结果=100.50" "值对象按值相等，运算返回新值"
expect_line "$OUT/facts.tsv" "100.0 与 100.00 equals=false，compareTo=0，放进 HashSet 后 2 个；Money 规范化后 1 个" "record 直接包 BigDecimal 时 scale 不同即不相等"
expect_line "$OUT/facts.tsv" "三种写法解析后 1 个值：+8613800138000" "手机号规范化后按值相等"
expect_line "$OUT/facts.tsv" "Tags=[VIP, STAFF, PRESS]，CopiedTags=[VIP]" "record 的列表组件可被外部修改，构造时复制则不能"
expect_line "$OUT/facts.tsv" "两个内容都是 [1, 2] 的数组组件：equals=false" "record 的数组组件按引用比较"
expect_line "$OUT/facts.tsv" "两个报名放进 HashSet 后剩 1 个（alice）；创建时分配标识：2 个" "保存前 id 为 null 的实体在 HashSet 中互相覆盖"
expect_line "$OUT/facts.tsv" "放进 HashSet 后再分配 id=42：contains=false" "分配 id 后 hashCode 改变，集合找不到它"
expect_line "$OUT/facts.tsv" "incompatible types: labs.ddd.values.Ids.AttendeeId cannot be converted to labs.ddd.values.Ids.SessionId" "类型化标识传反参数时编译失败"
expect_line "$OUT/facts.tsv" "String 标识参数传反：编译错误 0 个" "String 标识传反参数可以编译"
log "全部通过，输出在 $OUT"
