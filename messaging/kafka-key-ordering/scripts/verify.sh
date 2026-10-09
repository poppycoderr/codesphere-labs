#!/usr/bin/env bash
# 按业务键保序：分区数从 4 改成 8 之后键换分区、同一个分区里并行处理、失败消息延迟重试、不同的分区算法
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：单节点 Kafka 4.3.1（2 CPU、1 GB）与 temurin 25 容器（只用来编译）；约 1 分钟（不含拉取镜像）；结束时删除集群
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-kafka-key-ordering
IMAGE="apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837"
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
cleanup() { docker compose -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker compose -p "$PROJECT" up -d --wait >&2
rm -rf build/classes && mkdir -p build/classes
CLIENTS=$(maven_jar "org.apache.kafka:kafka-clients:4.3.1")
docker run --rm -v "$PWD:/w" -v "$CLIENTS:/clients.jar:ro" -w /w "$J25" javac --release 21 -cp /clients.jar -d build/classes src/Ordering.java
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD/build/classes:/app" --entrypoint java "$IMAGE" -cp "/app:/opt/kafka/libs/*" Ordering kafka:9092 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "kafka_image: $IMAGE" "kafka-clients: 4.3.1" "javac_image: $J25" "client_java: $(docker run --rm --entrypoint java "$IMAGE" -version 2>&1 | head -1)"
cat "$f" >&2
# 键到分区的映射由哈希决定，其余场景按固定顺序构造，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
resize.moved	20 个订单里有 7 个在扩容后换了分区：order-1（2→6）、order-3（3→7）、order-5（2→6）、order-8（1→5）、order-11（2→6）、order-12（3→7）、order-15（0→4）
resize.interleave	order-1 的三个版本分布在两个分区；新分区先被消费时的处理顺序：order-1:v3@分区6 → order-1:v1@分区2 → order-1:v2@分区2；按到达顺序覆盖，最后留下的是 v2
resize.within_partition	每个分区内部，同一个订单的版本都是递增的 = true
parallel.pool	同一个分区的 12 条消息交给 4 个线程的池：order-1 最后留下 v5，order-2 最后留下 v5（日志里最后一条都是 v6）
parallel.lanes	按订单号把消息分到 4 条各自串行的通道：order-1 最后留下 v6，order-2 最后留下 v6；两个订单是否并行处理 = true
retry.sequence	v1 处理失败，转入重试主题；v2 处理成功；重试 v1，按版本比较被丢弃
retry.final	直接覆盖：sku-1 最后是 v1；只接受更大的版本：sku-1 最后是 v2
partitioner	8 个分区下，20 个订单号按 murmur2 取模（Java 客户端默认）与按 CRC32 取模得到不同分区的有 18 个；murmur2 的计算与实际写入的分区全部一致
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
