#!/usr/bin/env bash
# Kafka 的吞吐从哪里来：批处理、压缩、确认级别、分区并行与热点 key
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：三节点 KRaft 集群（每个节点 1.5 CPU、1.5 GB）+ 一个 2 CPU 的客户端容器；约 5 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3 javac
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-kafka-throughput
IMAGE="apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837"
NET="${PROJECT}_default"
BIN=/opt/kafka/bin
BOOT="kafka1:9092,kafka2:9092,kafka3:9092"

docker compose -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -p "$PROJECT" up -d --wait >&2
client() { docker run --rm --network "$NET" --cpus 2 -v "$PWD/build/payload:/payload" -v "$PWD/build/classes:/app" --entrypoint "$1" "$IMAGE" "${@:2}"; }
topic() { client "$BIN/kafka-topics.sh" --bootstrap-server "$BOOT" --create --topic "$1" --partitions 1 --replication-factor "$2" ${3:+--config "$3"} >/dev/null; }

# 可压缩的消息体：字段名重复、取值有限，像业务 JSON
mkdir -p build/payload
python3 - <<'PY'
import json, random
random.seed(7)
with open("build/payload/orders.txt", "w") as f:
    for i in range(10000):
        f.write(json.dumps({"orderId": f"ORD-{random.randint(1, 10**9):010d}", "status": random.choice(["CREATED", "PAID", "SHIPPED"]),
                            "city": random.choice(["Hangzhou", "Shanghai", "Beijing", "Shenzhen"]), "amountCents": random.randint(100, 99999),
                            "channel": random.choice(["app", "web", "mini-program"]), "items": [{"sku": f"SKU-{random.randint(1, 500)}", "qty": 1}]}) + "\n")
PY

perf() {  # perf <名称> <topic> <消息来源参数> <生产者属性...>
  local name="$1" t="$2" payload="$3"; shift 3
  log "压测 $name"
  # shellcheck disable=SC2086
  client "$BIN/kafka-producer-perf-test.sh" --topic "$t" --num-records 200000 --throughput "${THROUGHPUT:--1}" $payload \
    --print-metrics --command-property bootstrap.servers="$BOOT" "$@" 2>/dev/null \
    | grep -E "records sent|producer-metrics:(batch-size-avg|records-per-request-avg|request-rate|compression-rate-avg):" \
    | sed -E "s/^/$name\t/; s/\{client-id=[^}]*\} *//" >>"$OUT/perf.tsv"
}

# 1. 批处理：同一个 topic，三组 batch.size / linger.ms，各跑 3 轮
topic batch 1
for round in 1 2 3; do
  perf "batch-off.$round" batch "--record-size 256" acks=1 batch.size=0 linger.ms=0
  perf "batch-default.$round" batch "--record-size 256" acks=1
  perf "batch-large.$round" batch "--record-size 256" acks=1 batch.size=262144 linger.ms=20
done

# 2. 压缩：每种算法一个 topic，消息体来自 orders.txt
for codec in none lz4 zstd; do
  topic "comp-$codec" 1
  perf "compression-$codec" "comp-$codec" "--payload-file /payload/orders.txt" acks=1 compression.type=$codec batch.size=65536 linger.ms=10
done
for codec in none lz4 zstd; do
  bytes=0
  for n in kafka1 kafka2 kafka3; do
    b=$(docker compose -p "$PROJECT" exec -T "$n" sh -c "find /tmp/kafka-logs/comp-$codec-0 -name '*.log' -exec cat {} + 2>/dev/null | wc -c" || echo 0)
    bytes=$((bytes + b))
  done
  printf 'disk.%s\t%s\n' "$codec" "$bytes" >>"$OUT/perf.tsv"
done

# 3. 确认级别：三副本、min.insync.replicas=2；固定 2 万条/秒，比较延迟；再不限速比较吞吐
topic acks 3 min.insync.replicas=2
for a in 0 1 all; do
  THROUGHPUT=20000 perf "acks-latency-$a" acks "--record-size 256" acks=$a
  perf "acks-max-$a" acks "--record-size 256" acks=$a
done

# 4. 分区并行与热点 key
rm -rf build/classes && mkdir -p build/classes
CLIENTS=$(maven_jar "org.apache.kafka:kafka-clients:4.3.1")
javac --release 21 -cp "$CLIENTS" -d build/classes src/Partitions.java
log "分区并行与热点 key"
client java -cp "/app:/opt/kafka/libs/*" Partitions "$BOOT" 2>/dev/null | grep -E "^(parallel|skew)" >"$OUT/partitions.tsv"

{ echo "image: $IMAGE"; echo "brokers: 3（KRaft，broker+controller），每个 1.5 CPU、1.5 GB、堆 512 MB"; echo "client: 同一镜像，2 CPU"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "kafka: 4.3.1"

python3 - "$OUT/perf.tsv" "$OUT/partitions.tsv" "$OUT/summary.tsv" <<'PY'
import re, sys, statistics
perf, parts, out = sys.argv[1:]
rows = [l.rstrip("\n").split("\t", 1) for l in open(perf, encoding="utf-8")]
def runs(prefix):
    res = {}
    for name, text in rows:
        if not name.startswith(prefix): continue
        d = res.setdefault(name, {})
        m = re.search(r"([\d.]+) records/sec \(([\d.]+) MB/sec\), ([\d.]+) ms avg latency, ([\d.]+) ms max latency, (\d+) ms 50th, (\d+) ms 95th, (\d+) ms 99th", text)
        if m: d.update(rps=float(m[1]), mbs=float(m[2]), avg=float(m[3]), p99=int(m[7]))
        m = re.search(r"producer-metrics:([\w-]+):\s*:?\s*([\d.]+)", text)
        if m: d[m[1]] = float(m[2])
    return res
lines = []
def median(prefix, key):
    return statistics.median(v[key] for v in runs(prefix).values())
b = {k: {m: median(f"batch-{k}.", m) for m in ("rps", "batch-size-avg", "records-per-request-avg")} for k in ("off", "default", "large")}
for k, label in (("off", "batch.size=0"), ("default", "默认（batch.size=16384，linger.ms=5）"), ("large", "batch.size=262144，linger.ms=20")):
    lines.append(f"batch.{k}\t{label}：{b[k]['rps']:,.0f} 条/秒，每个请求平均 {b[k]['records-per-request-avg']:.1f} 条，批大小平均 {b[k]['batch-size-avg']:,.0f} 字节（3 轮中位数）")
assert b["off"]["rps"] * 3 < b["default"]["rps"] < b["large"]["rps"] * 1.5, b
assert b["off"]["records-per-request-avg"] < 5 < b["default"]["records-per-request-avg"] < b["large"]["records-per-request-avg"], b
disk = {k: int(t) for k, t in rows if k.startswith("disk.") for k in [k.split(".")[1]]}
c = runs("compression-")
for codec in ("none", "lz4", "zstd"):
    v = c[f"compression-{codec}"]
    lines.append(f"compression.{codec}\t{codec}：{v['rps']:,.0f} 条/秒，{v['mbs']:.1f} MB/秒（压缩前），磁盘上的日志 {disk[codec] / 1048576:.1f} MB")
assert disk["zstd"] < disk["lz4"] < disk["none"] * 0.6, disk
a = runs("acks-")
for k in ("0", "1", "all"):
    lat, mx = a[f"acks-latency-{k}"], a[f"acks-max-{k}"]
    lines.append(f"acks.{k}\tacks={k}：固定 2 万条/秒时平均延迟 {lat['avg']:.1f}ms、p99 {lat['p99']}ms；不限速 {mx['rps']:,.0f} 条/秒")
assert a["acks-latency-all"]["avg"] > a["acks-latency-1"]["avg"] >= a["acks-latency-0"]["avg"], a
p = open(parts, encoding="utf-8").read()
secs = {k: float(s) for k, s in re.findall(r"^(\S+)\t.*?(?:最后一条|6 个消费者：) ?([\d.]+) 秒", p, re.M)}
assert secs["parallel.par-1"] > 4 * secs["parallel.par-6"], secs
assert "真正处理过消息的消费者 1 个" in p and "真正处理过消息的消费者 6 个" in p
assert secs["skew.hot-6"] > 4.5 and secs["skew.hot-6"] > 2 * secs["skew.even-6"], secs
lines += p.strip().splitlines()
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY
log "全部通过，输出在 $OUT（集群仍在运行，make clean 删除）"
