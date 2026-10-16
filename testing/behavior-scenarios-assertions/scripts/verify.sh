#!/usr/bin/env bash
# 从行为到场景到断言：四种断言写法的失败信息、逐条与分组断言、参数化显示名、测行为与测内部调用在重构后的结果（JUnit 6.1.3、AssertJ 3.27.7、Mockito 5.24.0）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 + temurin 25 容器（下载依赖、运行测试）与 temurin 25 容器（汇总报告）；约 2 分钟（首次需下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
rm -rf target
# 有些测试按设计会失败，用来展示失败信息；测试失败不中断构建，结果由下面的报告逐条比较
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B test -Dmaven.test.failure.ignore=true >"$OUT/maven.log" 2>&1 || fail "Maven 执行失败，见 $OUT/maven.log"
f="$OUT/output.tsv"
{ echo "env	java.version=25.0.4.1 junit=6.1.3 assertj=3.27.7 mockito=5.24.0"; docker run --rm -v "$PWD:/w" -w /w "$J25" java src/Report.java 4; } >"$f"
write_environment "$OUT/environment.txt" "maven_image: $MVN" "jdk25_image: $J25" "junit-jupiter: 6.1.3;assertj: 3.27.7;mockito: 5.24.0"
cat "$f" >&2
# 测试结果与失败信息是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 junit=6.1.3 assertj=3.27.7 mockito=5.24.0
FailureMessageTest.a_assertTrue_contains	失败：expected: <true> but was: <false>
FailureMessageTest.b_assertEquals_list	失败：expected: <[apple, banana, pear]> but was: <[apple, pear]>
FailureMessageTest.c_assertj_contains	失败：Expecting List12: ⏎ ["apple", "pear"] ⏎ to contain: ⏎ ["banana"]
FailureMessageTest.d_assertj_described	失败：[alice 的购物车] ⏎ Expecting actual: ⏎ ["apple", "pear"] ⏎ to contain exactly (and in same order):
GroupedAssertionTest.a_sequential	失败：expected: <100.00> but was: <90.00>
GroupedAssertionTest.b_assertAll	失败：订单摘要 (2 failures) ⏎ org.opentest4j.AssertionFailedError: total ==> expected: <100.00> but was: <90.00> ⏎ org.opentest4j.AssertionFailedError: status ==> expected: <PAID> but was: <NEW>
GroupedAssertionTest.c_softAssertions	失败：Multiple Failures (2 failures) ⏎ -- failure 1 -- ⏎ [total] ⏎ expected: 100.00
RefactorSafetyTest.behavior_v1	通过
RefactorSafetyTest.behavior_v2	通过
RefactorSafetyTest.interaction_v1	通过
RefactorSafetyTest.interaction_v2	失败：taxService.rate(); ⏎ Wanted 3 times: ⏎ -> at lab.RefactorSafetyTest.interaction_v2(RefactorSafetyTest.java:21) ⏎ But was 1 time:
ShippingRuleTest.fee(int, int, String) "10" 公斤 → 运费 "20"（"恰好 10 公斤"）	失败：expected: 20 ⏎ but was: 10
ShippingRuleTest.fee(int, int, String) "11" 公斤 → 运费 "20"（"超过 10 公斤"）	通过
ShippingRuleTest.fee(int, int, String) "9" 公斤 → 运费 "10"（"不满 10 公斤"）	通过
ShippingRuleTest.feeWithoutNames(int, int)[1] "9", "10"	通过
ShippingRuleTest.feeWithoutNames(int, int)[2] "10", "20"	失败：expected: 20 ⏎ but was: 10
ShippingRuleTest.feeWithoutNames(int, int)[3] "11", "20"	通过
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
