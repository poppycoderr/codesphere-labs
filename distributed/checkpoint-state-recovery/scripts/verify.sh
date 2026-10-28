#!/usr/bin/env bash
# Flink 本地执行环境中的 checkpoint 与算子失败恢复：恢复后的状态与数据源位置一致，最终计数 200（Flink 2.3.0、JDK 21）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/（输出目录需为相对路径）
# 资源：固定 digest 的 Maven 与 Temurin 镜像，2 CPU、2 GiB，无网络；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" build "$LABS_CACHE/m2"
rm -f "$OUT/attempts.txt"
MAVEN=maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525
JDK=eclipse-temurin@sha256:78ab9771b4650066c3ef748d46e05dbd6094d8bb34e0667a074486812efd655b
docker run --rm --cpus=2 --memory=2g -v "$PWD:/w" -v "$LABS_CACHE/m2:/root/.m2" -w /w "$MAVEN" sh -c 'rm -rf build/lib && mvn -q package dependency:copy-dependencies -DoutputDirectory=build/lib' >build/maven.log 2>&1
docker run --rm --hostname=localhost --label csl.experiment=checkpoint-state-recovery --network=none --cpus=2 --memory=2g -v "$PWD:/w:ro" -v "$PWD/$OUT:/out" -w /w "$JDK" timeout 120s java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp 'target/classes:build/lib/*' Recovery >"$OUT/results.txt" 2>build/flink.log
expect_line "$OUT/attempts.txt" "attempt=0 first_input=1 restored_count=0"
expect_regex "$OUT/results.txt" '^flink=2\.3\.0 java=21\.0\.12.* final_count=200$'
line=$(grep '^attempt=1 ' "$OUT/attempts.txt") || fail "没有重启记录"
first=$(sed 's/.*first_input=\([0-9]*\).*/\1/' <<<"$line"); restored=$(sed 's/.*restored_count=\([0-9]*\).*/\1/' <<<"$line")
[ "$restored" -gt 0 ] && [ "$restored" -eq $((first - 1)) ] || fail "恢复状态与数据源位置不一致：$line"
log "通过：恢复后 restored_count = first_input - 1（${line}）"
write_environment "$OUT/environment.txt" "maven_image: $MAVEN" "jdk_image: $JDK" 'flink: 2.3.0'
cat "$OUT/results.txt"
