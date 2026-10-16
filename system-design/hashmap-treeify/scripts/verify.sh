#!/usr/bin/env bash
# HashMap 冲突链树化：表容量不足 64 时先扩容、达到 64 后转红黑树；可比较与不可比较的键在树中查找时的 equals 调用次数
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC --add-opens java.base/java.util=ALL-UNNAMED src/TreeifyLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
treeify.threshold	同一个桶里依次放入冲突的键：7 个→表长 16、链表；8 个→表长 16、链表；9 个→表长 32、链表；10 个→表长 64、链表；11 个→表长 64、红黑树
treeify.presized	初始容量 128 的表放入 8 个冲突的键：表长 128、链表
lookup.non_comparable	1000 个 hashCode 全相同、不可比较的键（红黑树）：逐个查找共调用 equals 501499 次，平均 501 次；查一个不存在的键 1001 次
lookup.comparable	同样的键实现 Comparable（红黑树）：共调用 equals 10405 次，平均 10 次；查一个不存在的键 18 次
lookup.chain_estimate	如果一直是链表（JDK 8 之前），逐个查找的 equals 次数为 1+2+…+1000 = 500500，平均 500 次
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
