#!/usr/bin/env bash
# Spark 执行计划：过滤无 Exchange、分组计数先局部聚合再交换、热点键与加盐后的分区行数、加盐二次聚合等价、显式缓存（Spark 4.2.0 local[2]）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/（输出目录需为相对路径）
# 资源：固定 digest 的 spark:4.2.0-python3 镜像，2 CPU、3 GiB，无网络；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT/events" build
# A dedicated output tree avoids retaining prior application event logs.
find "$OUT/events" -mindepth 1 -delete
IMAGE=spark@sha256:d283f4305f6b307a7b1effce4fce9f6866f4b536b168809ddbaadb6aea793694
docker run --rm --user 0 --cpus=2 --memory=3g --network=none -e SPARK_LOCAL_IP=127.0.0.1 -v "$PWD/src:/src:ro" -v "$PWD/$OUT:/out" "$IMAGE" /opt/spark/bin/spark-submit --driver-memory 1g /src/plan.py >"$OUT/stdout.txt" 2>build/spark.log
write_environment "$OUT/environment.txt" "spark_image: $IMAGE"
cat "$OUT/results.json"
