#!/usr/bin/env bash
# 金额的来历：引用当前价格、成交价快照、按有效期分版本的价格、报价有效期、发票与订单版本的凭证链（PostgreSQL 18）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：一个 PostgreSQL 容器（不暴露端口）和 JDK 25 客户端容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PROJECT=csl-provenance
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1 || fail "容器启动失败"
COORDS="org.postgresql:postgresql:42.7.14"
JARS=""; for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC --network "${PROJECT}_default" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC -cp "${JARS#:}" src/ProvenanceLab.java 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed "s/^/image: /"; echo "jdk_image: $J25"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "postgresql: 18（compose.yaml 固定 digest）" "jdbc: postgresql 42.7.14"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 postgres=18.6
reference.reprice	10 月 1 日下单 o-1（A×2、B×1），当时合计 230.00；10 月 20 日 A 调价到 120 后再查这张历史订单：270.00
snapshot.reprice	下单时把单价抄进订单行，同样调价后 o-2 的合计：230.00；但快照只有一个数字，问「这个 100 是标价、会员价还是促销价」答不上来
version.overlap	给 A 再加一个与现有标价重叠的版本：被拒绝（conflicting key value violates exclusion constraint "price_version_product_id_kind_valid_excl"）
version.trace	10 月 5 日下单 o-3，单价与来源：90.00 来自 PROMO 版本 #3 [2026-10-01,2026-10-08)
version.as_of	按日期回答「A 当天的标价」：10 月 5 日 100.00，10 月 25 日 120.00
version.delete_used	删除已被订单引用的促销价版本：被拒绝（update or delete on table "price_version" violates foreign key constraint "order_line_ver_price_version_id_fkey" on table "order_line_ver"）
quote.validity	q-1 报价 200、有效期至 10 月 10 日：10 月 9 日接受 → 按报价 200.00 成交；10 月 12 日接受 → 报价已过期，需重新报价
chain.drift	开票后订单被改成 200（版本 2）：发票仍是 230；按「发票记录的订单版本 ≠ 订单当前版本」能查出不一致的发票 1 张；只存 order_id 时，这张发票看起来与订单「对不上」，却说不清是哪一方变了
chain.freeze	加上「已开票的订单金额不可改」的触发器后再改：被拒绝（订单 o-4 已开票，不能修改金额，请开红字发票或更正单）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
