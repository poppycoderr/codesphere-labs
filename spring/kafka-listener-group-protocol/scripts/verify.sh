#!/usr/bin/env bash
# Spring Kafka 的 @KafkaListener：默认的重平衡协议与分配器，切换到 KIP-848 新协议需要改什么（Kafka 4.3.1，Testcontainers）
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3 docker
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[0-9-]+T[0-9:.+-]+ +/<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

f="$OUT/facts.tsv"
expect_line "$OUT/test-results.txt" "tests=3 failures=0" "3 个测试全部通过"
expect_line "$f" "@KafkaListener 默认：group.protocol = classic，partition.assignment.strategy = [class org.apache.kafka.clients.consumer.RangeAssignor, class org.apache.kafka.clients.consumer.CooperativeStickyAssignor]，收到消息=true" "默认：经典协议，RangeAssignor 在前"
expect_line "$f" "只设置 group.protocol=consumer：group.protocol = consumer，group.remote.assignor = null，收到消息=true" "只加一个配置项即可切换到新协议并正常消费"
expect_line "$f" "应用启动失败，根因 ConfigException：session.timeout.ms cannot be set when group.protocol=CONSUMER" "保留 session.timeout.ms 时应用启动失败"
expect_line "$OUT/dependencies.txt" "org.springframework.kafka:spring-kafka:jar:4.1.1" "spring-kafka 4.1.1"
expect_line "$OUT/dependencies.txt" "org.apache.kafka:kafka-clients:jar:4.2.1" "kafka-clients 4.2.1（Spring Boot 4.1.1 管理的版本）"
log "全部通过，输出在 $OUT"
