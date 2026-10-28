#!/usr/bin/env bash
# 分桶库存：拆分余数、空桶误判售罄、客户端迁移丢扣减、缩容后旧路由、带版本号的原子迁移与并发扩缩容
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Redis 容器 2 CPU、1 GB，JDK 25 客户端容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
C=(docker compose -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
f="$OUT/output.tsv"
docker run --rm --network csl-bucket-layout_default -v "$PWD:/w" -w /w "$IMG" java src/BucketLayout.java >"$f" 2>"$OUT/details.txt"
write_environment "$OUT/environment.txt" "redis: 8.10.1（compose.yaml 固定 digest）" "jdk_image: $IMG"
cat "$f" >&2
# 并发场景只断言守恒（卖出、剩余、负数桶、最终版本）；旧版本被拒的次数随调度变化，记在 details.txt
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
split.integer-division	10 件拆 3 个桶，每桶 10/3：合计 9
split.remainder	余数分给前面的桶：4/3/3，合计 10
split.more-buckets	3 件拆 5 个桶：[1, 1, 1, 0, 0]，有 2 个空桶
split.zero	0 个桶：拒绝（桶数必须大于 0：0）
empty.home-only	只扣自己的桶：10 个请求抢 10 件（4/3/3），成功 7，判为售罄 3，判售罄时其他桶里还有 6 件
empty.probe	空桶时依次试其他桶：10 个请求抢 10 件（4/3/3），成功 10，判为售罄 0，判售罄时其他桶里还有 0 件
migrate.client-side	迁移读总数后卖出 3 件，写新桶后库存 10：总共能卖 13 件（初始 10）
stale.no-version	不校验版本，DECR 已删除的 4 号桶得到 -1，这笔扣减不在任何现存的桶里；现存库存仍是 10
stale.versioned	带版本校验：旧版本请求返回 -2（被拒），重读布局（版本 2、3 个桶）后扣减成功，剩余 9
concurrent	8 个线程扣减 1000 件，期间桶数调整 5 次：卖出 1000，剩余 0，负数桶 0，最终版本 6
EXPECTED
log "全部通过，输出在 $OUT"
