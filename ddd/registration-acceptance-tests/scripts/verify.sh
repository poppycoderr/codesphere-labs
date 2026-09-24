#!/usr/bin/env bash
# 事件风暴产物进入验收测试：同一组 Given/When/Then 先检查初稿模型（缺 R3），再检查修订后的模型；规则表、词汇表与代码的可追踪检查
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete

log "初稿模型（model=draft）：预期 R3 的用例失败"
mvn -B -q -Dstyle.color=never test -Dmodel=draft >"$OUT/maven-test-draft.log" 2>&1 && fail "初稿模型不应全部通过" || true
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results-draft.txt"

log "修订后的模型"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
python3 scripts/trace.py "$OUT/test-results.txt" "$OUT/traceability.tsv"

write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "junit: 5.13.4"
for f in "$OUT"/maven-test*.log; do normalize_paths <"$f" >"$f.tmp" && mv "$f.tmp" "$f"; done

expect_line "$OUT/test-results-draft.txt" "tests=12 failures=3" "初稿模型 12 个用例失败 3 个"
[ "$(grep -c '^FAIL' "$OUT/test-results-draft.txt")" = "$(grep -c '^FAIL  RegistrationAcceptanceTest  R3 ' "$OUT/test-results-draft.txt")" ] \
  || fail "初稿模型失败的用例应全部属于 R3"
log "通过：初稿模型失败的用例全部属于 R3"
expect_line "$OUT/test-results.txt" "tests=12 failures=0" "修订后的模型 12 个用例全部通过"
expect_line "$OUT/traceability.tsv" "规则 8 条，用例 12 个，术语 8 个，问题 0 个" "每条规则都有用例、每个术语都能在代码中找到"
log "全部通过，输出在 $OUT"
