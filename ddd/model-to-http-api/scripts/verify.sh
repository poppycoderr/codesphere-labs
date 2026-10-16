#!/usr/bin/env bash
# 从模型到 HTTP 接口：整体替换与丢失更新、If-Match、428、PUT 缺字段与 PATCH、状态字段与命令端点、幂等键、202 受理（Spring MVC 7.0.9 的 MockMvc）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 + temurin 25 容器（下载依赖）与 temurin 25 容器；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
rm -rf build/lib; mkdir -p build/lib
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B dependency:copy-dependencies -DoutputDirectory=/w/build/lib >/dev/null 2>&1 || fail "依赖下载失败"
ls build/lib | sort >"$OUT/classpath.txt"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" sh -c 'java -Duser.timezone=UTC -cp "$(ls build/lib/*.jar | tr "\n" ":")" src/ApiLab.java' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "spring-webmvc: 7.0.9"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 spring=7.0.9
lost_update	两个客户端都读到 {"address":"北京","note":"","status":"NEW"}；客服 PUT 改地址为上海，用户随后基于旧数据 PUT 加备注：最终 {"address":"北京","note":"放门口","status":"NEW"}，客服的修改丢了，两次都是 200
if_match	两个客户端都带 If-Match: "1"：第一个 200，第二个 412（版本已变为 2），最终 {"address":"上海","note":"","status":"NEW"}
if_match.missing	不带 If-Match 的 PUT：428（缺少 If-Match）
put_missing_field	PUT 只带 address：{"address":"上海","note":null,"status":"NEW"}，note 被清空：PUT 的语义是整体替换
patch	PATCH 只带 address：{"address":"深圳","note":"放门口","status":"NEW"}
status_field	已发货的订单，PUT 把 status 写成 CANCELLED：{"address":"北京","note":null,"status":"CANCELLED"}，没有经过「已发货不能取消」
command	改用 POST /orders/o-d/cancellation：409（已发货不能取消）
idempotency	响应丢失后客户端重发同一个 POST：不带幂等键新增 2 个订单；带同一个 Idempotency-Key：第一次 /orders/o-3，重发 /orders/o-3，新增 1 个
accepted	生成报表：202，Location /reports/j-1，状态资源 {"status":"RUNNING"}
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
