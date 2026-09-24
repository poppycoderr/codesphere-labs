#!/usr/bin/env bash
# 遗留系统的精益切片：字符化测试锁住旧退款行为、切出接缝、影子比对找出未写明的规则、按比例分流与回滚
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
{ head -1 src/test/resources/approved-refunds.tsv; grep '^E' src/test/resources/approved-refunds.tsv; } >"$OUT/approved-edge-cases.tsv"
shasum -a 256 src/test/resources/approved-refunds.tsv | awk '{print "approved-refunds.tsv sha256=" $1}' >"$OUT/approved-digest.txt"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "junit: 5.13.4"
normalize_paths <"$OUT/maven-test.log" >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=3 failures=0" "3 个测试全部通过"
expect_line "$OUT/facts.tsv" "旧服务 2007 个用例与已批准的输出一致；切出接缝后的服务同样一致" "字符化测试锁住旧行为，切出接缝后行为不变"
expect_line "$OUT/facts.tsv" "按直觉实现的新规则影子比对 2007 个用例：不一致 1748 个，其中退款档位不同 19 个、只差不足一元的零头 1729 个" "影子比对找出两条未写明的规则"
expect_line "$OUT/facts.tsv" "不一致 0 个" "显式写出旧规则后影子比对一致"
expect_line "$OUT/facts.tsv" "10% → 202 个；50% → 989 个；0% → 0 个；100% → 2007 个；返回值全部与旧算法一致" "按比例分流、调回 0 与全量切换"
expect_line "$OUT/approved-edge-cases.tsv" "E0	19950	4320	9900" "恰好 72 小时：旧系统只退一半"
expect_line "$OUT/approved-edge-cases.tsv" "E3	19950	4380	19900" "73 小时：全额，但抹去 50 分零头"
log "全部通过，输出在 $OUT"
