#!/usr/bin/env bash
# 取模、哈希环、虚拟节点与有界负载：10 万个键在 10 个节点上的负载与加入第 11 个节点后的迁移比例，以及一个占 20% 请求的热点键
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC src/HashLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 keys=100000
modulo	取模：10 个节点 最多 10242、最少 9723，最多是平均的 1.02 倍；加入第 11 个节点后迁移 90.8%
ring.1	哈希环（每个节点 1 个点）：最多 22569、最少 577，最多是平均的 2.26 倍；加节点迁移 12.2%（理想值 9.1%）
ring.10	虚拟节点 10 个：最多 14560、最少 6487，最多是平均的 1.46 倍；加节点迁移 7.2%
ring.100	虚拟节点 100 个：最多 11911、最少 9034，最多是平均的 1.19 倍；加节点迁移 8.3%
ring.1000	虚拟节点 1000 个：最多 10463、最少 9561，最多是平均的 1.05 倍；加节点迁移 9.4%
bounded.1.25	虚拟节点 10 个 + 有界负载 c=1.25：最多 12500、最少 6490，最多是平均的 1.25 倍；加节点迁移 9.2%
bounded.1.1	虚拟节点 10 个 + 有界负载 c=1.1：最多 11001、最少 7183，最多是平均的 1.10 倍；加节点迁移 12.9%
hotkey	一个键占 20% 的请求：它所在节点承担 27.7% 的请求，是平均的 2.77 倍；虚拟节点和有界负载按键的个数均衡，挡不住单个键的热度
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
