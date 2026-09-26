#!/usr/bin/env bash
# Kafka 消费组重平衡：三种方式（经典 eager、经典协作式、KIP-848 新协议）下的扩容停顿、慢成员的影响、
# 处理超时导致的重复消费、静态成员身份，以及客户端默认值与新协议的配置限制
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：三节点 KRaft 集群（每个节点 1.5 CPU、1.5 GB）+ 2 CPU 的客户端容器；约 12 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3 javac
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-kafka-rebalance
IMAGE="apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837"
BOOT="kafka1:9092,kafka2:9092,kafka3:9092"

docker compose -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -p "$PROJECT" up -d --wait >&2
rm -rf build/classes && mkdir -p build/classes
CLIENTS=$(maven_jar "org.apache.kafka:kafka-clients:4.3.1")
javac --release 21 -cp "$CLIENTS" -d build/classes src/Rebalance.java
run() { log "场景 $*"; docker run --rm --network "${PROJECT}_default" --cpus 2 -v "$PWD/build/classes:/app" --entrypoint java "$IMAGE" -cp "/app:/opt/kafka/libs/*" Rebalance "$BOOT" "$@" 2>/dev/null >>"$OUT/facts.tsv"; }

run defaults
run config
for round in 1 2; do
  for mode in eager coop consumer; do
    run scale-healthy "$mode"
    run scale-slow "$mode"
  done
done
for mode in eager consumer; do
  run dup "$mode"
  run static "$mode"
  run dynamic "$mode"
done

{ echo "image: $IMAGE"; echo "brokers: 3（KRaft，broker+controller），每个 1.5 CPU、1.5 GB、堆 512 MB；消费组相关的服务端配置均为默认值"; echo "client: 同一镜像，2 CPU"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "kafka: 4.3.1"
python3 scripts/check.py "$OUT/facts.tsv" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（集群仍在运行，make clean 删除）"
