#!/usr/bin/env bash
# 计划、发生与记录三个时刻：用 updatedAt 当事实时间、计划时刻存成 Instant、迟到的事实按哪个日期汇总、取消与执行的先后、计划与实际之差
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS=""
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/TimeLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
audit_as_fact	支付后第二天改了收货地址：用 updatedAt 当支付时间，报表里这笔订单的支付时间是 2026-10-16T08:00:00Z；单独记录 paidAt 的是 2026-10-15T01:05:00Z
planned.instant	纽约用户约定每天 9:00 提醒：把第一次的 Instant 每天加 24 小时，夏令时结束后在当地 [2026-10-30 09:00, 2026-11-02 08:00]
planned.local	存「本地时间 09:00 + 时区」、每次按当天计算：[2026-10-30 09:00, 2026-11-02 09:00]
late.by_received	按系统收到的日期汇总：{2026-10-15=10, 2026-10-16=25}
late.by_occurred	按实际发生的日期汇总：{2026-10-15=30, 2026-10-16=5}
late.after_close	10-15 的日报在 2026-10-16T00:10:00Z 已经发出：d2 的读数 在日报发出之后才到，需要一条更正记录（10-15 由 10 改为 30），而不是悄悄改掉已发出的数字
cancel.by_received	用户 2026-10-16T01:59:58Z 点了取消，请求 2026-10-16T02:00:05Z 才到；任务 2026-10-16T02:00:03Z 已执行。按「收到时间」判断：取消晚于执行，拒绝取消
cancel.by_requested	按「用户提出的时间」判断：取消早于计划时刻 2026-10-16T02:00:00Z，应当视为已取消，需要补偿已执行的动作；规则选哪一种是业务决定，两个时间都要记下来
schedule.delay	3 个计划任务，按「实际执行时刻 − 计划时刻 ≥ 5 分钟」算迟到：2 个；只记执行时刻（没有计划时刻）就算不出来
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
