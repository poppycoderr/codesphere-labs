#!/usr/bin/env bash
# 重复的知识与偶然的相似：税率写两处、昵称与优惠码合并规则、提取时改变的失败语义与求值次数
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
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/DuplicationLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
knowledge.before	开票与退款各写一遍税率 6%：开票 106.00，退款 106.00
knowledge.change	税率改成 9%，只改到了开票：开票 109.00，退款仍是 106.00
knowledge.single_source	两处都调用同一个 TaxPolicy，改一处：开票 109.00，退款 109.00
similar.before	昵称与优惠码今天的规则一样（4 到 20 位字母数字）：昵称 "bob2026" true，优惠码 "七折优惠" false
similar.merged_change	合并成 isValidShortText 之后按昵称的新需求修改：昵称 "小明" true，优惠码 "七折优惠" 也变成 true
failure.inline	原写法：调用方收到 DbError（写入失败）
failure.extracted	提取成 runAndNotify 之后：调用方收到 NotifierDown（通知服务不可用）
failure.fixed	提取时把通知失败挂到原异常上：调用方收到 DbError（写入失败），suppressed 里有 NotifierDown
evaluation.eager	日志关闭时循环 1000 次：内联的 if 里 expensive() 调用 0 次；提取成 logIf(on, expensive()) 后 1000 次；参数改成 Supplier 后 0 次
evaluation.short_circuit	cheapCheck() && consumeQuota() 执行 100 次，额度被扣 0 次；提取成 both(cheapCheck(), consumeQuota()) 后被扣 100 次
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
