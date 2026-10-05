#!/usr/bin/env bash
# 失败重跑：同一个缺陷在不重跑、重跑两次、重跑并设置 failOnFlakeCount 时的构建结果；顺序依赖的测试；按概率模拟
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.9.11 + temurin 25 容器；首次运行要下载依赖（缓存在仓库的 .cache/m2repo），约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
MVN="maven@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c"
mkdir -p "$LABS_CACHE/m2repo" build
# run <输出文件> <mvn 参数...>：运行 mvn clean test，保留测试统计、失败信息与构建结论，去掉耗时
run() {
  local out="$1"; shift
  set +e
  docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -B -Dstyle.color=never "$@" clean test >build/last.log 2>&1
  local code=$?
  set -e
  { echo "maven_exit_code=$code"
    grep -E "Tests run:|BUILD|Flakes:|  Run [0-9]+:|failOnFlakeCount" build/last.log | sed -E 's/, Time elapsed: [0-9.]+ s//; s/^\[ERROR\] Failed to execute goal [^:]+:[^:]+:[^:]+:test \(default-test\) on project [^:]+: /[ERROR] /; s/ -> \[Help 1\]//'; } >"$out"
}
run "$OUT/no-rerun.txt"
run "$OUT/rerun-2.txt" -Dsurefire.rerunFailingTestsCount=2
grep -o '<flakyFailure[^>]*>' target/surefire-reports/TEST-labs.RateTableTest.xml >"$OUT/rerun-2-report.txt"
run "$OUT/rerun-2-fail-on-flake.txt" -Dsurefire.rerunFailingTestsCount=2 -Dsurefire.failOnFlakeCount=1
run "$OUT/order-together.txt" -Dsuite=labs.InventoryTest -Dsurefire.rerunFailingTestsCount=2
run "$OUT/order-alone.txt" "-Dsuite=labs.InventoryTest#b_firstDelivery"
docker run --rm -v "$PWD:/w" -w /w "$MVN" java src/RetryOdds.java >"$OUT/retry-odds.tsv"
write_environment "$OUT/environment.txt" "maven_image: $MVN" "container_java: $(docker run --rm "$MVN" java -version 2>&1 | head -1)" "junit-jupiter: 6.1.3" "maven-surefire-plugin: 3.6.0"
for x in no-rerun rerun-2 rerun-2-report rerun-2-fail-on-flake order-together order-alone; do echo "== $x" >&2; cat "$OUT/$x.txt" >&2; done

expect_line "$OUT/no-rerun.txt" "maven_exit_code=1" "不重跑：构建失败"
expect_regex "$OUT/no-rerun.txt" "Tests run: 1, Failures: 1, Errors: 0, Skipped: 0$" "不重跑：1 个失败"
expect_line "$OUT/rerun-2.txt" "maven_exit_code=0" "重跑 2 次：构建成功"
expect_regex "$OUT/rerun-2.txt" "Run 1: RateTableTest.usdRate:10 expected: <712> but was: <0>" "重跑 2 次：第 1 次的失败信息保留在输出里"
expect_regex "$OUT/rerun-2.txt" "Run 2: PASS" "重跑 2 次：第 2 次通过"
expect_regex "$OUT/rerun-2.txt" "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Flakes: 1" "重跑 2 次：失败数为 0，flake 数为 1"
expect_regex "$OUT/rerun-2-report.txt" "flakyFailure message=.expected: &lt;712&gt; but was: &lt;0&gt;" "XML 报告里有 flakyFailure 元素"
expect_line "$OUT/rerun-2-fail-on-flake.txt" "maven_exit_code=1" "failOnFlakeCount=1：构建失败"
expect_regex "$OUT/rerun-2-fail-on-flake.txt" "There is 1 flake and failOnFlakeCount is set to 1" "failOnFlakeCount=1：失败原因是 flake"
expect_line "$OUT/order-together.txt" "maven_exit_code=1" "顺序依赖：一起运行时构建失败"
expect_regex "$OUT/order-together.txt" "Run 1: InventoryTest.b_firstDelivery:21 expected: <3> but was: <8>" "顺序依赖：第 1 次得到 8"
expect_regex "$OUT/order-together.txt" "Run 3: InventoryTest.b_firstDelivery:21 expected: <3> but was: <14>" "顺序依赖：第 3 次得到 14"
expect_line "$OUT/order-alone.txt" "maven_exit_code=0" "顺序依赖：单独运行第二个测试时通过"
# 模拟使用固定种子，输出是确定的
diff - "$OUT/retry-odds.tsv" <<'EXPECTED' || fail "模拟结果与预期不一致"
fail_probability	reruns	builds_failed	formula
0.50	0	50.163%	50.000%
0.50	1	25.205%	25.000%
0.50	2	12.484%	12.500%
0.50	3	6.304%	6.250%
0.30	0	29.958%	30.000%
0.30	1	9.042%	9.000%
0.30	2	2.600%	2.700%
0.30	3	0.774%	0.810%
0.10	0	9.942%	10.000%
0.10	1	0.952%	1.000%
0.10	2	0.082%	0.100%
0.10	3	0.008%	0.010%
0.02	0	2.015%	2.000%
0.02	1	0.043%	0.040%
0.02	2	0.002%	0.001%
0.02	3	0.000%	0.000%
EXPECTED
log "全部通过，输出在 $OUT"
