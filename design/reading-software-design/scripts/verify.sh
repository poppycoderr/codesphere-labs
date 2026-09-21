#!/usr/bin/env bash
# 三个成熟系统的「模型 → 接口 → 实现」切片：Spring 容器、Kafka Producer、JDK Stream
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
require mvn docker
log "解析依赖（spring-context 7.0.9、kafka-clients 4.3.1）"
mvn -B -q dependency:copy-dependencies -DoutputDirectory=build/lib >/dev/null
ls build/lib >"$OUT/dependencies.txt"
CP=$(ls build/lib/*.jar | tr '\n' ':')

java src/StreamSlice.java >"$OUT/stream-slice.txt"
java -cp "$CP" src/SpringSlice.java >"$OUT/spring-slice.txt" 2>&1

log "启动单节点 Kafka 4.3.1（compose 项目 csl-reading-design）"
docker compose -f compose.yaml up -d --wait >&2
CID=$(docker compose -f compose.yaml ps -q kafka)
docker run --rm --network "container:$CID" -v "$PWD:/w" -w /w "$JDK_IMAGE" \
  java -cp "$(ls build/lib/*.jar | sed 's#^#/w/#' | tr '\n' ':')" src/KafkaSlice.java >"$OUT/kafka-slice.txt" 2>&1
write_environment "$OUT/environment.txt" "kafka_broker: apache/kafka:4.3.1" "jdk_in_container: $(docker run --rm "$JDK_IMAGE" java -version 2>&1 | head -1)"

expect_line "$OUT/stream-slice.txt" "只组装流水线、还没调用终止操作：filter 执行 0 次，map 执行 0 次" "Stream：终止操作前什么都不执行"
expect_line "$OUT/stream-slice.txt" "findFirst() 之后：结果 70，filter 执行 7 次，map 执行 1 次" "Stream：findFirst 只过滤 7 个元素"
expect_regex "$OUT/spring-slice.txt" "Bean 定义 7 个.*实例 0 个|7 个 Bean 定义" "Spring：refresh 前有 7 个 Bean 定义"
expect_regex "$OUT/kafka-slice.txt" "这 300 条消息产生的生产请求：1 个" "Kafka：300 条消息合成 1 个生产请求"
python3 - "$OUT/kafka-slice.txt" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
send_ms = float(re.search(r"300 次 send\(\) 全部返回用时 ([\d.]+) ms", t).group(1))
first = float(re.search(r"第一个回调在第 (\d+) ms", t).group(1))
assert send_ms < 100, f"300 次 send() 应远快于 linger.ms：{send_ms}"
assert first >= 500, f"第一个回调应在 linger.ms=500 之后：{first}"
print(f"通过：300 次 send() 用 {send_ms}ms 返回，第一个回调在第 {first:.0f}ms")
PY
