#!/usr/bin/env bash
# 依赖注入容器的绑定身份与依赖图：只按 Class 与按类型加限定符注册、检查与解析的差别，菱形与真环，延迟边，泛型引用相等性（JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，jakarta.inject-api 从 Maven Central 下载到缓存；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="jakarta.inject:jakarta.inject-api:2.0.1"
JARS=""; for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC -cp "${JARS#:}" src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "jakarta.inject-api: 2.0.1"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
class.bindings	注册 Source@raw 与 Source@daily 后，容器里有 1 个绑定：[DailySource]
class.check	检查 Source@daily：循环依赖：Source → Source
class.resolve	解析 Source@raw：构造过程中重入：Source
key.bindings	注册 Source@raw 与 Source@daily 后，容器里有 2 个绑定：[RawSource, DailySource]
key.check	检查 Source@daily：通过
key.resolve	解析 Source@raw：得到 raw
mixed.check	绑定按完整键、检查路径只记 Class，检查 Source@daily：循环依赖：Source → Source
key.real_cycle	Source@a 依赖 Source@b、Source@b 又依赖 Source@a：循环依赖：Source@a → Source@b → Source@a
diamond.path	菱形依赖，按当前路径判断：通过
diamond.visited	菱形依赖，只看是否访问过：报告循环依赖（在 Base 处）
lazy.check	Clock 只持有 Provider<Ticker>，Ticker 依赖 Clock，配置期检查：通过
lazy.get	解析 Clock：通过；之后调用 clock.ticker.get()：通过
eager.check	同样结构但构造器里立刻 get()，配置期检查：通过
eager.get	解析 EagerClock：构造过程中重入：EagerClock
missing	缺少依赖：NeedsMissing → Source@absent
ref.class_equals	两处写的 new TypeRef<List<String>>(){}：按 getClass() 比较相等=false，按捕获的 Type 比较相等=true
named.equals	两个独立创建的 @Named("raw") 注解实例 equals=true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
