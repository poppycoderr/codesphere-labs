#!/usr/bin/env bash
# 计数器溢出：Math.abs(MIN_VALUE)、负数取余、轮询下标越界、floorMod 与掩码、hashCode 为 MIN_VALUE 的字符串（JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
abs.min	Math.abs(Integer.MIN_VALUE) = -2147483648；Math.abs(Long.MIN_VALUE) = -9223372036854775808
abs.exact	Math.absExact(Integer.MIN_VALUE)：ArithmeticException：Overflow to represent absolute value of Integer.MIN_VALUE
rem.negative	-7 % 3 = -1，Math.floorMod(-7, 3) = 2
rr.naive	计数器从 MAX_VALUE-2 开始，Math.abs(n) % 3 连取 4 次：n0，n1，ArrayIndexOutOfBoundsException：Index -2 out of bounds for length 3，n1
rr.floormod	同样起点，Math.floorMod(n, 3) 连取 6 次：n0，n1，n1，n2，n0，n1（溢出处连续两次选中 n1，分配有一次不均，但不会越界）
rr.mask	同样起点，(n & Integer.MAX_VALUE) % 3 连取 6 次：n0，n1，n0，n1，n2，n0
hash.min	"polygenelubricants".hashCode() = -2147483648；Math.abs(hash) % 10 = -8；Math.floorMod(hash, 10) = 2
hash.negative	"key-0"…"key-99999" 的 hashCode() % 16 为负数的有 940 个（共 100000 个）
time	每秒 10000 次自增，从 0 到 Integer.MAX_VALUE 需要约 2.5 天；换成 long 约 2925 万年
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
