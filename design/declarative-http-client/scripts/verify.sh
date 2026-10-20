#!/usr/bin/env bash
# 声明式 HTTP 客户端：Spring HTTP Interface 的代理、路径与查询参数编码、错误状态、超时、默认方法，对照手写 JDK 动态代理的编码错误（Spring Framework 7.0.9、JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 容器下载依赖，temurin 25 容器在进程内启动 JDK HttpServer 作为后端；约 30 秒（含两次 3 秒的慢请求）
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
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "spring-framework: 7.0.9"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	spring=7.0.9 java=25.0.4.1+1-LTS
proxy	createClient 返回的对象是 JDK 动态代理：true
param.name	@PathVariable 不写名字（单文件启动没有 -parameters）：java.lang.IllegalArgumentException：Name for argument of type [java.lang.String] not specified, and parameter name information not available via reflection. Ensure that the compiler uses the '-parameters' flag.
path.encode	get("a/b c")：GET rawPath=/users/a%2Fb%20c path=/users/a/b c
query.encode	search("x&y+z", ["a","b"])：GET rawPath=/search path=/search rawQuery=q=x%26y%2Bz&tag=a&tag=b
post	create("{\"sku\":1}")：POST rawPath=/orders path=/orders body={"sku":1}
default	调用接口默认方法 twice("7")：GET rawPath=/users/7 path=/users/7 | GET rawPath=/users/7 path=/users/7；服务端收到 2 个请求
status.404	返回类型 String，服务端 404：HttpClientErrorException$NotFound：404 Not Found: "error 404"
status.404.entity	返回类型 ResponseEntity<String>，服务端 404：HttpClientErrorException$NotFound：404 Not Found: "error 404"
status.500	服务端 500：HttpServerErrorException$InternalServerError：500 Internal Server Error: "error 500"
timeout.default	默认请求工厂，服务端 3 秒后才响应：等了约 3 秒，结果「GET rawPath=/slow path=/slow」
timeout.read	读取超时设为 1 秒：约 1 秒后「ResourceAccessException：I/O error on GET request for "http://127.0.0.1:<port>/slow": Reques…」
hand.raw	手写代理直接替换 {id}，get("a/b")：GET rawPath=/users/a/b path=/users/a/b
hand.space	手写代理直接替换 {id}，get("a b")：java.lang.IllegalArgumentException：Illegal character in path at index 30: http://127.0.0.1:<port>/users/a b
hand.urlencoder	手写代理用 URLEncoder 编码路径，get("a b")：GET rawPath=/users/a+b path=/users/a+b
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
