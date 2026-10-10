#!/usr/bin/env bash
# 事件时间与窗口：按到达时间与按发生时间归窗、允许晚到的时长、只在窗口关闭后输出、一个时间戳错得很远的事件（Kafka Streams 的 TopologyTestDriver）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="org.apache.kafka:kafka-streams:4.3.1 org.apache.kafka:kafka-streams-test-utils:4.3.1 org.apache.kafka:kafka-clients:4.3.1 org.slf4j:slf4j-api:2.0.20 org.rocksdb:rocksdbjni:10.1.3 com.fasterxml.jackson.core:jackson-databind:2.22.3 com.fasterxml.jackson.core:jackson-core:2.22.3 com.fasterxml.jackson.core:jackson-annotations:2.22"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/EventTimeLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 kafka-streams=4.3.1
setup	一分钟的滚动窗口，统计每个窗口里的点击数；事件写成（发生时刻 → 到达时刻），单位是 08:00:00 之后的秒数
truth	事件：10→11、20→21、55→105（晚到 50 秒）、70→71、80→81。按发生时间，08:00 这一分钟有 3 个，08:01 有 2 个
by_arrival	按到达时间归窗：{08:00=2, 08:01=3}
by_event_time.grace_5m	按发生时间归窗，允许晚到 5 分钟：{08:00=3, 08:01=2}
by_event_time.no_grace	按发生时间归窗，不允许晚到：{08:00=2, 08:01=2}
by_event_time.grace_10s	按发生时间归窗，允许晚到 10 秒（晚到的那个到达时，流里见过的最大时间戳是第 80 秒）：{08:00=2, 08:01=2}
by_event_time.grace_30s	按发生时间归窗，允许晚到 30 秒：{08:00=3, 08:01=2}
suppress.idle	只在窗口关闭后输出（suppress），事件 10、20、70，之后再没有新事件：{}
suppress.advanced	同样的设置，后面又来了 95、155 两个事件：{08:00=2, 08:01=2}
future_timestamp	事件 10、20、30、40 中间混进一个时间戳在一小时之后的事件（设备时钟错了），允许晚到 5 分钟：{08:00=1, 09:00=1}
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
