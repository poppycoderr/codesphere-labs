#!/usr/bin/env bash
# 包之间的依赖：互相依赖、端口反转、引用了业务包的 common 包；jdeps 的包依赖与不稳定度，ArchUnit 的无环规则（JDK 25、ArchUnit 1.5.1）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 + temurin 25 容器（下载依赖）与 temurin 25 容器；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
rm -rf build/lib; mkdir -p build/lib
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B dependency:copy-dependencies -DoutputDirectory=/w/build/lib >/dev/null 2>&1 || fail "依赖下载失败"
ls build/lib | sort >"$OUT/classpath.txt"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" sh -c 'java -Duser.timezone=UTC -cp "$(ls build/lib/*.jar | tr "\n" ":")" src/ComponentLab.java' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "archunit: 1.5.1"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 archunit=1.5.1
v1.jdeps	包依赖 {billing=[order], order=[billing]}；不稳定度 billing I=0.50 order I=0.50
v1.archunit	slices().matching("shop.(*)..").should().beFreeOfCycles()：违反：Cycle detected: Slice billing -> Slice order -> Slice billing 1. Dependencies of Slice billing
v2.jdeps	包依赖 {billing=[order]}；不稳定度 billing I=1.00 order I=0.00
v2.archunit	slices().matching("shop.(*)..").should().beFreeOfCycles()：通过
v3.jdeps	包依赖 {billing=[order], common=[order], order=[common]}；不稳定度 billing I=1.00 common I=0.50 order I=0.33
v3.archunit	slices().matching("shop.(*)..").should().beFreeOfCycles()：违反：Cycle detected: Slice common -> Slice order -> Slice common 1. Dependencies of Slice common
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
