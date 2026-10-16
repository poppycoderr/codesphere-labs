#!/usr/bin/env bash
# 保行为的方法提取：2000 个随机订单分别交给原方法与四个拆分版本，比较返回值、异常与副作用顺序
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS=""
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/ExtractionLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
setup	2000 个随机订单（固定种子）：含空订单、数量为 0 或负数、缺货商品、优惠码与会员的各种组合
extracted	按原顺序拆成 validate、subtotal、applyCoupon、applyVip、reserveAll：返回值或异常不同 0 个，副作用不同 0 个
merged_loop	把预占并进校验循环：返回值或异常不同 0 个，副作用不同 271 个；第一个差异 O14：原方法 [IllegalArgumentException: bad qty S4；]，新版本 [IllegalArgumentException: bad qty S4；reserve S2x3]
swapped_discounts	两个折扣的先后对调：返回值或异常不同 95 个，副作用不同 95 个；第一个差异 O0：原方法 [returned 222；reserve S6x2，reserve S4x3，audit O0 total=222]，新版本 [returned 221；reserve S6x2，reserve S4x3，audit O0 total=221]
split_validation	先查完所有数量、再查所有库存：返回值或异常不同 36 个，副作用不同 0 个；第一个差异 O290：原方法 [IllegalStateException: out of stock S9；]，新版本 [IllegalArgumentException: bad qty S1；]
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
