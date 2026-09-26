#!/usr/bin/env bash
# Kafka 投递语义的边界：读—处理—写事务的中止与提交、transactional.id 隔离旧实例、自动提交 + 异步处理丢消息、
# min.insync.replicas 与 ISR 收缩（停掉两个 follower）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：1 个 controller + 3 个 broker（每个 1 CPU、1 GB）；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3 javac
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-kafka-delivery
IMAGE="apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837"
BOOT="kafka1:9092,kafka2:9092,kafka3:9092"

docker compose -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -p "$PROJECT" up -d --wait >&2
rm -rf build/classes && mkdir -p build/classes
CLIENTS=$(maven_jar "org.apache.kafka:kafka-clients:4.3.1")
javac --release 21 -cp "$CLIENTS" -d build/classes src/Delivery.java
run() { log "场景 $1"; docker run --rm --network "${PROJECT}_default" --cpus 2 -v "$PWD/build/classes:/app" --entrypoint java "$IMAGE" -cp "/app:/opt/kafka/libs/*" Delivery "$BOOT" "$1" 2>/dev/null >>"$OUT/facts.tsv"; }

run tx
run fence
run autocommit
run isr-setup
run isr-full
log "停掉 kafka2、kafka3，等 ISR 收缩到只剩 leader"
docker compose -p "$PROJECT" stop kafka2 kafka3 >&2
run isr-shrunk

{ echo "image: $IMAGE"; echo "controller: 1 个（独立部署）；brokers: 3 个，每个 1 CPU、1 GB、堆 384 MB；replica.lag.time.max.ms=5000"; echo "client: 同一镜像，2 CPU"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "kafka: 4.3.1"

f="$OUT/facts.tsv"
expect_line "$f" "中止事务后：read_committed 读到 0 条，read_uncommitted 读到 10 条，tx-in 的已提交 offset null" "中止的事务：消息在日志里，read_committed 看不到，offset 没有前进"
expect_line "$f" "重新处理并提交后：read_committed 读到 10 条，tx-in 的已提交 offset 10" "提交后输出与 offset 一起生效"
expect_line "$f" "旧实例继续发送：InvalidProducerEpochException；随后 abortTransaction 抛出 ProducerFencedException" "被隔离的旧实例继续发送"
expect_line "$f" "旧实例直接提交：ProducerFencedException" "被隔离的旧实例直接提交"
expect_line "$f" "read_committed 读到 1 条：旧实例写入的消息都随事务中止" "旧实例的消息不可见"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"已处理 (\d+) 条，已提交 offset (\d+)，重启后有 (\d+) 条", t)
done, off, lost = map(int, m.groups())
assert off == 100 and done < 100 and lost == 100 - done, m.groups()
print(f"通过：自动提交 + 异步处理，已处理 {done} 条，offset 已提交到 100，{lost} 条不会再被消费")
PY
expect_line "$f" "ISR [1, 2, 3]：isr-strict acks=all 成功" "ISR 完整时 acks=all 成功"
expect_line "$f" "ISR [1]：isr-strict acks=all 默认重试 TimeoutException（等满 delivery.timeout.ms=5000 才失败），retries=0 NotEnoughReplicasException；acks=1 成功" "ISR 低于 min.insync.replicas：acks=all 被拒绝，acks=1 仍然成功"
expect_line "$f" "ISR [1]：isr-default（min.insync.replicas=1）acks=all 成功" "min.insync.replicas=1 时 acks=all 退化为只写 leader"
log "全部通过，输出在 $OUT（集群仍在运行，make clean 删除）"
