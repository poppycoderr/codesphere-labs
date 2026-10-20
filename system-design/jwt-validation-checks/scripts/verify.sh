#!/usr/bin/env bash
# 访问令牌验签之后还要校验什么：默认解码器与加上签发者、受众、类型校验后，对错受众、错签发者、ID Token、过期、无 exp、错密钥、alg=none、HS256 算法混淆的处理；scope 子串匹配（Spring Security 7.1.1、Nimbus 10.9.1、JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 容器下载依赖，temurin 25 容器运行；约 20 秒（首次需下载依赖）
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
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" sh -c 'java -Duser.timezone=UTC -cp "$(ls build/lib/*.jar | tr "\n" ":")" src/Lab.java' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "spring-security: 7.1.1" "nimbus-jose-jwt: 10.9.1"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	spring-security=7.1.1 nimbus-jose-jwt=10.9.1 java=25.0.4.1+1-LTS
default.at_jwt	typ=at+jwt、其余全部正确的访问令牌：默认解码器 拒绝：An error occurred while attempting to decode the Jwt: the given typ value needs to be one of [JWT]
default.ok	签发者、受众正确：默认解码器（typ=JWT）接受（aud=[orders-api]，iss=https://idp.example）
strict.ok	签发者、受众正确：加上签发者、受众、at+jwt 类型校验 接受（aud=[orders-api]，iss=https://idp.example）
default.aud_other	受众是 billing-api：默认解码器（typ=JWT）接受（aud=[billing-api]，iss=https://idp.example）
strict.aud_other	受众是 billing-api：加上签发者、受众、at+jwt 类型校验 拒绝：An error occurred while attempting to decode the Jwt: The aud claim is not valid
default.iss_other	签发者是另一个身份服务（同一把密钥）：默认解码器（typ=JWT）接受（aud=[orders-api]，iss=https://other-idp.example）
strict.iss_other	签发者是另一个身份服务（同一把密钥）：加上签发者、受众、at+jwt 类型校验 拒绝：An error occurred while attempting to decode the Jwt: The iss claim is not valid
default.id_token	一个 ID Token（受众是网页应用、带 nonce）：默认解码器（typ=JWT）接受（aud=[web-app]，iss=https://idp.example）
strict.id_token	一个 ID Token（受众是网页应用、带 nonce）：加上签发者、受众、at+jwt 类型校验 拒绝：An error occurred while attempting to decode the Jwt: The aud claim is not valid
default.expired_30s	30 秒前过期：默认解码器（typ=JWT）接受（aud=[orders-api]，iss=https://idp.example）
strict.expired_30s	30 秒前过期：加上签发者、受众、at+jwt 类型校验 接受（aud=[orders-api]，iss=https://idp.example）
default.expired_5m	5 分钟前过期：默认解码器（typ=JWT）拒绝：An error occurred while attempting to decode the Jwt: Jwt expired at <时间>
strict.expired_5m	5 分钟前过期：加上签发者、受众、at+jwt 类型校验 拒绝：An error occurred while attempting to decode the Jwt: Jwt expired at <时间>
default.no_exp	没有 exp：默认解码器（typ=JWT）接受（aud=[orders-api]，iss=https://idp.example）
strict.no_exp	没有 exp：加上签发者、受众、at+jwt 类型校验 接受（aud=[orders-api]，iss=https://idp.example）
default.other_key	用另一把同 kid 的密钥签名：默认解码器（typ=JWT）拒绝：An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature
strict.other_key	用另一把同 kid 的密钥签名：加上签发者、受众、at+jwt 类型校验 拒绝：An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature
default.alg_none	alg=none 的未签名令牌：默认解码器（typ=JWT）拒绝：Unsupported algorithm of none
strict.alg_none	alg=none 的未签名令牌：加上签发者、受众、at+jwt 类型校验 拒绝：Unsupported algorithm of none
default.hs256_pubkey	HS256，用公钥字节当密钥签名：默认解码器（typ=JWT）拒绝：An error occurred while attempting to decode the Jwt: Signed JWT rejected: Another algorithm expected, or no m…
strict.hs256_pubkey	HS256，用公钥字节当密钥签名：加上签发者、受众、at+jwt 类型校验 拒绝：An error occurred while attempting to decode the Jwt: Signed JWT rejected: Another algorithm expected, or no m…
strict.no_exp.required	没有 exp：再加上 setAllowEmptyExpiryClaim(false) 的时间校验器 拒绝：An error occurred while attempting to decode the Jwt: exp is required
scope.orders.read_orders.write	scope="orders.read orders.write"：indexOf("orders.read") > 0 → false；按空格拆成集合精确匹配 → true
scope.profile_orders.readonly	scope="profile orders.readonly"：indexOf("orders.read") > 0 → true；按空格拆成集合精确匹配 → false
scope.billing.read_orders.read	scope="billing.read orders.read"：indexOf("orders.read") > 0 → true；按空格拆成集合精确匹配 → true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
