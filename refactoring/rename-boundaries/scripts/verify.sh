#!/usr/bin/env bash
# 改名的边界：JSON 字段名、反射按名字查找、按名字或序号存下的枚举、配置键，以及 @JsonProperty 保留对外名字
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="tools.jackson.core:jackson-databind:3.2.3 tools.jackson.core:jackson-core:3.2.3 com.fasterxml.jackson.core:jackson-annotations:2.22"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/RenameLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 jackson=3.2.3
json.wire	改名前写出 {"sku":"A1","qty":3}；改名后写出 {"sku":"A1","quantity":3}
json.old_reader	还没升级的消费方（按 qty 读、忽略不认识的字段）读新消息：OrderLineV1[sku=A1, qty=0]
json.new_reader	已升级的一方读队列里积压的旧消息：OrderLineV2[sku=A1, quantity=0]
json.compat	改代码里的名字、用 @JsonProperty 保留对外名字：写出 {"sku":"A1","qty":3}，读旧消息得到 OrderLineV2Compat[sku=A1, quantity=3]
reflection.old	配置里写着方法名 calc，改名前反射调用：fee=10
reflection.new	方法改名为 calculateFee 后，同一份配置：抛出 NoSuchMethodException（RenameLab$ServiceV2.calc(int)）
enum.stored	数据库里存的是 WAIT_PAY；枚举常量改名为 AWAITING_PAYMENT 之后读回：抛出 IllegalArgumentException（No enum constant RenameLab.StatusV2.WAIT_PAY）
enum.ordinal	如果存的是序号 ordinal()：WAIT_PAY 存为 0；新版本在最前面插入 CREATED 之后，按序号读回来是 CREATED
config	代码把配置键 order.max-qty 改成 order.max-quantity，部署环境里的配置没改：读到 null，回退到代码里的默认值
compiler	直接调用的地方由编译器保证：new ServiceV2().calculateFee(5) = fee=10
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
