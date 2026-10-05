#!/usr/bin/env bash
# 浮点求和：表示误差、顺序、float 累加器饱和、补偿求和、分块归约得到不同结果、long 经过 double 之后的精度
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/SumLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 全部是单线程的 IEEE 754 运算，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4
repr.point_one	new BigDecimal(0.1) = 0.1000000000000000055511151231257827021181583404541015625
repr.sum	0.1 + 0.2 = 0.30000000000000004；与 0.3 相等 = false
repr.ten_times	0.1 累加 10 次 = 0.9999999999999999；与 1.0 相等 = false
order.ulp	1e16 附近相邻两个 double 相差 2.0
order.big_first	1e16 之后加 10 个 1.0 = 10000000000000000
order.small_first	先加 10 个 1.0 再加 1e16 = 10000000000000010
order.assoc	(0.1+0.2)+0.3 = 0.6000000000000001；0.1+(0.2+0.3) = 0.6
float.saturate	float 累加 2000 万个 1.0f = 16777216（2^24 = 16777216）
float.tenth	float 累加 1000 万个 0.1f = 1087937.0；double 累加 = 999999.9998389754
amount.intended	按分用 long 累加 = 49996274454.21
amount.exact_of_doubles	这些 double 的精确和与它相差 -1.1313882356672745E-9
amount.naive	逐个累加 = 49996274454.2123870849609375；误差 0.0023870860923257357
amount.kahan	Kahan 补偿 误差 -9.143959555143327E-7
amount.neumaier	Neumaier 补偿 误差 -9.143959555143327E-7
amount.pairwise	两两归并 误差 -8.543790486764333E-6
amount.stream	DoubleStream.sum()（顺序流） 误差 -9.143959555143327E-7
amount.sorted_asc	升序排序后逐个累加 误差 0.0015173351157632357
amount.chunked	分块各自累加再合并，误差：1 块 0.0023870860923257357；2 块 -0.0018548572670492643；3 块 9.832774985757357E-4；4 块 0.0011358653892007357；6 块 2.3559683451323567E-4；8 块 3.1189077982573567E-4；16 块 4.8736685404448567E-4；共 7 个不同的结果
amount.float	用 float 逐个累加 = 49472577536
amount.rounded	逐个累加的和保留两位小数 = 49996274454.21；与按分累加一致 = true
cancel	[1, 1e100, 1, -1e100]：逐个累加 0.0，Kahan 0.0，Neumaier 2.0，DoubleStream.sum() 0.0
long.to_double	long 9007199254740993 转 double 再转回 = 9007199254740992（2^53 = 9007199254740992）
long.sum_as_double	3 个 9007199254740993 用 double 累加 = 27021597764222976；用 long 累加 = 27021597764222979
bigdecimal.ctor	new BigDecimal(0.1) 与 BigDecimal.valueOf(0.1) 相等 = false；new BigDecimal("1.10").equals(new BigDecimal("1.1")) = false，compareTo = 0
nan	含一个 NaN 的数组求和 = NaN；NaN == NaN = false；Double.compare(0.0, -0.0) = 1，0.0 == -0.0 = true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
