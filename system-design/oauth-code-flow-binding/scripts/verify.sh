#!/usr/bin/env bash
# 授权码、PKCE 与 state：本地 Spring Authorization Server 上的授权码流程，码重复使用、redirect_uri 不匹配、客户端不匹配、公共客户端缺少 PKCE、错误 verifier、plain 方法、刷新令牌复用与轮换、撤销后内省与本地验签、ID Token 当访问令牌（Spring Boot 4.1.1、Spring Security 7.1.1、JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 容器下载依赖，temurin 25 容器在进程内启动 Spring Boot 授权服务器（只监听容器内回环）并用 HttpClient 走授权码流程；约 1 分钟（首次需下载依赖）
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
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "spring-boot: 4.1.1" "spring-security: 7.1.1"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	spring-boot=4.1.1 spring-security=7.1.1 java=25.0.4.1+1-LTS
unauthenticated	未登录时发起授权请求：302 → /login
confidential.no_pkce	机密客户端（有 client_secret）不带 code_challenge，默认设置：302 → https://web.example/callback，error=invalid_request（OAuth 2.0 Parameter: code_challenge）
authorize	登录后发起授权请求：302 → https://web.example/callback，带 code，state=s-123
token	用授权码换令牌：200，access_token 有，refresh_token 有，id_token 有
introspect.before	内省第一次拿到的访问令牌：active=true
code.reuse	同一个授权码再换一次：400，error=invalid_grant
refresh.after_code_reuse	同一次授权签发的 refresh_token 再用来刷新：400，error=invalid_grant
introspect.after_reuse	授权码被重复使用之后，再内省第一次的访问令牌：active=false
redirect.unregistered	授权请求的 redirect_uri 未登记：400
redirect.prefix	redirect_uri 只比登记的多一段路径：400
redirect.token_mismatch	换令牌时的 redirect_uri 与授权请求不同：400，error=invalid_grant
client.mismatch	web 拿到的授权码由 web-rotate 去换：400，error=invalid_grant
state.echo	state 由客户端生成，授权服务器原样带回（本次 state=s-123）；校验它是客户端的责任
pkce.missing	公共客户端不带 code_challenge：302 → https://spa.example/callback，error=invalid_request（OAuth 2.0 Parameter: code_challenge）
pkce.wrong	带 S256 挑战，换令牌时给错误的 verifier：400，error=invalid_grant
pkce.missing_verifier	带挑战，换令牌时不给 verifier：400，error=invalid_client
pkce.ok	给正确的 verifier（公共客户端）：200，access_token 有，refresh_token 无，id_token 无
pkce.plain	code_challenge_method=plain：302 → https://spa.example/callback，error=invalid_request（OAuth 2.0 Parameter: code_challenge_method）
refresh.default_setting	TokenSettings 默认值：reuseRefreshTokens=true
refresh.first	web 用 refresh_token 刷新：200，access_token 有，refresh_token 有，id_token 有；新的 refresh_token 与原来的相同
refresh.again	同一个 refresh_token 再刷新一次：200，access_token 有，refresh_token 有，id_token 有
refresh.explicit_false	web-rotate 显式设 reuseRefreshTokens=false：刷新后新的 refresh_token 与原来的不同
rotate.replay_old	轮换之后再用原来的 refresh_token：400，error=invalid_grant
rotate.after_replay	旧的被重放之后，用轮换得到的新 refresh_token：200，access_token 有，refresh_token 有，id_token 有
revoke	撤销一个访问令牌之后：内省 active=false；资源服务器用 JWK 本地验签：接受
id_token_as_bearer	把 ID Token 当作访问令牌交给只验签的资源服务器解码器：接受（aud=[web]）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
