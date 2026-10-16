#!/usr/bin/env bash
# 替身证明了什么：宽泛匹配与严格桩、替身与真实实现共享的契约测试、录制响应与对方当前格式（JUnit 6.1.3、Mockito 5.24.0、H2 2.4.240）
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
{ echo "env	java.version=25.0.4.1 junit=6.1.3 mockito=5.24.0 h2=2.4.240"; docker run --rm -v "$PWD:/w" -w /w "$J25" java src/Report.java 4; } >"$f"
write_environment "$OUT/environment.txt" "maven_image: $MVN" "jdk25_image: $J25" "junit-jupiter: 6.1.3;mockito: 5.24.0;h2: 2.4.240"
cat "$f" >&2
# 测试结果与失败信息是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 junit=6.1.3 mockito=5.24.0 h2=2.4.240
InMemoryOrdersContractTest.byCustomer_newestFirst	失败：Expecting actual: ⏎ ["o-1", "o-2", "o-3"] ⏎ to contain exactly (and in same order): ⏎ ["o-2", "o-3", "o-1"]
InMemoryOrdersContractTest.insert_duplicateId_rejected	失败：Expecting code to raise a throwable.
JdbcOrdersContractTest.byCustomer_newestFirst	通过
JdbcOrdersContractTest.insert_duplicateId_rejected	通过
MatcherTest.a_anyMatcher_lenient	通过
MatcherTest.b_exactArgument_lenient	错误：订单不存在
MatcherTest.c_exactArgument_strictStubs	错误：Strict stubbing argument mismatch. Please check: ⏎ - this invocation of 'find' method: ⏎ repo.find("c-9"); ⏎ -> at lab.Orders$CancelService.cancel(Orders.java:69)
RecordedResponseTest.a_againstRecordedStub	通过
RecordedResponseTest.b_againstRunningProvider	失败：expected: "PAID" ⏎ but was: "UNKNOWN"
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
