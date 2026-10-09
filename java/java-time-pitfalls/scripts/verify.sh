#!/usr/bin/env bash
# java.time：时间点与本地时间、夏令时前后的加一天与加 24 小时、月末运算、Period 与天数、格式化字母、解析的宽严、相等
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC  src/TimeLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 tzdata=2026b
instant.zones	时间点 2026-10-09T02:00:00Z：上海 2026-10-09T10:00，洛杉矶 2026-10-08T19:00
local.ambiguous	本地时间 2026-10-09T10:00 按上海解释是 2026-10-09T02:00:00Z，按纽约解释是 2026-10-09T14:00:00Z，相差 12 小时
dst.plus_days	2026-03-07T12:00-05:00[America/New_York] 加 1 天 = 2026-03-08T12:00-04:00[America/New_York]，实际经过 23 小时
dst.plus_hours	2026-03-07T12:00-05:00[America/New_York] 加 24 小时 = 2026-03-08T13:00-04:00[America/New_York]
dst.gap	纽约不存在的本地时间 2026-03-08T02:30 被解析为 2026-03-08T03:30-04:00[America/New_York]
dst.overlap	纽约出现两次的本地时间 2026-11-01T01:30 默认取 2026-11-01T01:30-04:00[America/New_York]，另一个是 2026-11-01T01:30-05:00[America/New_York]，两者相差 60 分钟
dst.day_length	纽约 2026-03-08 这一天从 2026-03-08T05:00:00Z 到 2026-03-09T04:00:00Z，共 23 小时
month.end	2026-01-31 加 1 个月 = 2026-02-28；再加 1 个月 = 2026-03-28；直接加 2 个月 = 2026-03-31
period.days	2026-01-31 到 2026-03-01：Period 是 P1M1D，getDays() = 1；ChronoUnit.DAYS.between = 29
duration.int_overflow	30 天的毫秒数用 int 计算：30 * 24 * 60 * 60 * 1000 = -1702967296；Duration.ofDays(30).toMillis() = 2592000000
format.week_year	2026-12-27 用 YYYY-MM-dd（Locale.US）格式化 = 2027-12-27；用 yyyy-MM-dd = 2026-12-27
format.hour	2026-10-09T15:05 用 hh:mm 格式化 = 03:05；用 HH:mm = 15:05
format.day_of_year	2026-02-10 用 yyyy-MM-DD 格式化 = 2026-02-41
parse.smart	默认（SMART）解析 2026-02-30 = 2026-02-28；解析 2026-02-32 = 抛出 DateTimeParseException
parse.strict_yyyy	STRICT 加 yyyy 解析合法日期 2026-02-10 = 抛出 DateTimeParseException
parse.strict_uuuu	STRICT 加 uuuu 解析 2026-02-10 = 2026-02-10；解析 2026-02-30 = 抛出 DateTimeParseException
equality	2026-10-09T10:00+08:00[Asia/Shanghai] 与 2026-10-09T02:00Z[UTC]：equals = false，isEqual = true，toInstant 相等 = true
legacy.date	new Date(2026, 10, 9) 表示的日期是 3926-11-09
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
