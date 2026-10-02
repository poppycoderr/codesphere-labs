#!/usr/bin/env bash
# 「快照 + 增量」同步：取快照与订阅的先后、重复与丢失、乱序、裁剪过的副本
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/SyncLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 来源、网络与副本在一个线程里模拟，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4
order.snapshot_first	先取快照（序号 3）后订阅，其间来源产生了序号 4：副本 {A=10, B=25, C=30}，来源 {A=6, B=25, C=30}，一致=false
order.subscribe_first.blind	先订阅后取快照（序号 4），缓存的增量全部应用：副本 {A=2, B=25, C=30}，来源 {A=6, B=25, C=30}，一致=false
order.subscribe_first.by_seq	同样的顺序，丢弃序号不大于快照序号的增量：副本 {A=6, B=25, C=30}，丢弃 1 条，一致=true
faults.blind	序号 4 重复投递、序号 5 丢失，不检查序号：副本 {A=2, B=20, C=29}，来源 {A=6, B=25, C=29}，一致=false，副本没有任何报错
faults.by_seq	同样的输入，检查序号：忽略重复 1 条，发现缺口 1 次（收到序号 6 时还缺序号 5）；重新取快照后副本 {A=6, B=25, C=29}，一致=true
reorder.window	序号 5、6 先到、4 后到，最多暂存 2 条：需要重新同步=false，副本 {A=6, B=25, C=29}，一致=true
truncate	副本只保存库存最多的前 2 个（B、C），随后 C 售罄：副本里还剩 {B=20}；来源里有库存的是 {A=10, B=20}，前 2 个应该是 A 和 B
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
