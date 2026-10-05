#!/usr/bin/env bash
# 伪共享：两个线程各写各的变量，相邻字段、手工填充、@Contended、数组元素、只读线程、普通字段各自的每次操作耗时
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，需要至少 2 个空闲核；约 3 分钟。计时类实验，运行期间不要同时跑其他负载
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
EXPORT="--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java $EXPORT -XX:-RestrictContended src/FalseSharingLab.java >"$f" 2>"$OUT/stderr.log"
# 不加 -XX:-RestrictContended：应用类上的 @Contended 被忽略，只看字段布局
docker run --rm -v "$PWD:/w" -w /w "$J25" java $EXPORT src/FalseSharingLab.java layout 2>/dev/null | grep '^layout' >"$OUT/layout-default.tsv"
docker run --rm "$J25" sh -c 'echo "getconf LEVEL1_DCACHE_LINESIZE: $(getconf LEVEL1_DCACHE_LINESIZE)"; java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E "ContendedPaddingWidth|RestrictContended|EnableContended"' >"$OUT/cache-line.txt"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "host_cpu: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)" "host_cache_line: $(sysctl -n hw.cachelinesize 2>/dev/null || echo unknown)"
cat "$f" "$OUT/layout-default.tsv" "$OUT/cache-line.txt" >&2

val() { awk -F'\t' -v k="$1" '$1==k {print $2}' "$f"; }
# ge <a> <b> <说明>：a >= b
ge() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a>=b)}' || fail "$3（$1 < $2）"; log "通过：$3（$1 ≥ $2）"; }
le() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a<=b)}' || fail "$3（$1 > $2）"; log "通过：$3（$1 ≤ $2）"; }

expect_line "$f" "layout.adjacent	a@16 b@24" "相邻字段相距 8 字节"
expect_regex "$f" "^layout.contended	a@144 b@280$" "打开开关后 @Contended 的两个字段相距 136 字节"
expect_regex "$OUT/layout-default.tsv" "^layout.contended	a@16 b@24$" "默认配置下应用类的 @Contended 不生效"
expect_regex "$OUT/cache-line.txt" "ContendedPaddingWidth += 128" "@Contended 的默认填充宽度是 128 字节"
solo=$(val write.solo)
ge "$(val ratio.adjacent_vs_contended)" 3 "相邻字段比 @Contended 慢 3 倍以上"
le "$(val write.contended)" "$(awk -v s="$solo" 'BEGIN{print s*2}')" "@Contended 的两线程耗时不超过单线程的 2 倍"
le "$(val write.padded120)" "$(awk -v s="$solo" 'BEGIN{print s*2}')" "两侧各 120 字节填充的两线程耗时不超过单线程的 2 倍"
ge "$(val array.neighbors)" "$(awk -v s="$(val array.32_apart)" 'BEGIN{print s*3}')" "数组相邻元素比相隔 32 个元素慢 3 倍以上"
[ "$(val scan.128_bytes_apart)" = "0/16" ] || fail "相距 128 字节的元素不应互相拖慢"
log "通过：相距 128 字节的 16 对元素都没有互相拖慢"
ge "$(val read.next_to_writer)" "$(awk -v s="$(val read.contended_writer)" 'BEGIN{print s*1.5}')" "只读线程在写者旁边慢 1.5 倍以上"
le "$(val plain.adjacent)" 0.1 "普通字段的自增循环每次不到 0.1 纳秒：循环被合并"
log "全部通过，输出在 $OUT"
