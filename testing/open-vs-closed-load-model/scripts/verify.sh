#!/usr/bin/env bash
# 负载模型：同一个会卡顿 2 秒的服务，用闭合模型、开放模型、单线程按时间表发送、发压能力不足四种方式各测 12 秒
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 1 分钟。计时类实验，只断言范围
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/LoadModelLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2

# val <键> [字段名]：取一行的第二列，或该行里 name=value 形式的某个字段
val() { awk -F'\t' -v k="$1" -v n="${2:-}" '$1==k { if (n=="") print $2; else for (i=2;i<=NF;i++) if (index($i,n"=")==1) print substr($i,length(n)+2) }' "$f"; }
between() { awk -v v="$1" -v lo="$2" -v hi="$3" 'BEGIN{exit !(v>=lo && v<=hi)}' || fail "$4：$1 不在 [$2, $3]"; log "通过：$4（$1）"; }

between "$(val closed.sent_during_stall)" 1 20 "闭合模型：卡顿的 2 秒里只发出了与用户数相当的请求"
between "$(val closed.from_send slow)" 1 20 "闭合模型：超过 100 毫秒的样本不超过 20 个"
between "$(val closed.from_send p99)" 0 50 "闭合模型：p99 不超过 50 毫秒"
between "$(val closed.from_send max)" 1900 2500 "闭合模型：最大值约 2 秒"
between "$(val open.achieved_rate)" 990 1010 "开放模型：达到目标速率"
between "$(val open.sent_during_stall)" 1900 2100 "开放模型：卡顿的 2 秒里照常发出约 2000 个请求"
between "$(val open.from_intended slow)" 2000 6000 "开放模型：超过 100 毫秒的样本在 2000 个以上"
between "$(val open.from_intended p99)" 1500 2500 "开放模型：p99 在 1.5 秒以上"
between "$(val paced_single.from_send slow)" 1 3 "单线程按时间表发送、从实际发送时刻计时：慢样本只有 1 到 3 个"
between "$(val paced_single.from_send p99)" 0 50 "同上：p99 不超过 50 毫秒"
between "$(val paced_single.from_intended slow)" 200 1200 "同一次运行、从预定时刻计时：慢样本在 200 个以上"
between "$(val paced_single.from_intended p99)" 1500 2500 "同上：p99 在 1.5 秒以上"
between "$(val undersized.achieved_rate)" 100 1000 "发压能力不足：实际速率不到目标 2000 的一半"
between "$(val undersized.from_send p99)" 0 50 "发压能力不足、从实际发送时刻计时：p99 不超过 50 毫秒"
between "$(val undersized.from_intended p99)" 3000 12000 "发压能力不足、从预定时刻计时：p99 在 3 秒以上"
log "全部通过，输出在 $OUT"
