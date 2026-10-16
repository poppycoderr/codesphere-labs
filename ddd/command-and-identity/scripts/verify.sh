#!/usr/bin/env bash
# 请求、可信身份与命令：请求体绑定到领域对象与命令对象、身份来源、按租户查找、表单绑定的允许字段（Spring MVC 7.0.9 的 MockMvc）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 + temurin 25 容器（下载依赖到仓库的 .cache/m2repo）与 temurin 25 容器；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
rm -rf build/lib; mkdir -p build/lib
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B dependency:copy-dependencies -DoutputDirectory=/w/build/lib >/dev/null 2>&1 || fail "依赖下载失败"
ls build/lib | sort >"$OUT/classpath.txt"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" sh -c 'java -cp "$(ls build/lib/*.jar | tr "\n" ":")" src/CommandLab.java' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "maven_image: $MVN" "jdk25_image: $J25" "spring-webmvc: 7.0.9" "jackson: 3.2.3"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 spring=7.0.9
bind.entity	请求体直接绑定到领域对象，客户端在 JSON 里带上 tenantId、ownerId、status：保存的是 Order[tenant=t-bob, owner=bob, sku=A1, qty=2, status=PAID]
bind.command	绑定到只含 sku、qty 的命令对象，身份取自请求上下文，同样的 JSON：保存的是 Order[tenant=t-alice, owner=alice, sku=A1, qty=2, status=NEW]
lookup.by_id	租户 t-alice 的用户按 id 读取 t-bob 的订单（Order[tenant=t-bob, ow…），只按 id 查：Order[tenant=t-bob, owner=bob, sku=B9, qty=1, status=NEW]
lookup.by_tenant	按（租户，id）查：not found
form.open	表单绑定到有 setRole 的对象，请求参数里多带 role=admin：nickname=al, role=admin
form.allowed	在 @InitBinder 里只允许 nickname：nickname=al, role=member
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
