#!/usr/bin/env bash
# Jakarta REST 的默认行为：返回值与状态码、HEAD 与 OPTIONS、异常与映射器、参数缺省与转换失败的状态码、转换方式的选择（Jersey 4.0.3、JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 容器下载依赖，temurin 25 容器在进程内启动 Jersey（JDK HttpServer，只监听容器内回环）；约 1 分钟（首次需下载依赖）
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
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "jersey: 4.0.3"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	jersey=4.0.3 java=25.0.4.1+1-LTS
resp.string	返回 String：HTTP 200，Content-Length: 5，body「hello」
resp.null	返回 null：HTTP 204，body「」
resp.void	void 方法：HTTP 204，body「」
resp.explicit	返回 Response.status(201)：HTTP 201，X-Trace: t1，Content-Length: 7，body「created」
resp.head	对只有 @GET 的资源发 HEAD：HTTP 200，body「」；GET 方法被调用 1 次
resp.options	没写 @OPTIONS 时发 OPTIONS：HTTP 200，Allow: GET,HEAD,OPTIONS,PUT，Content-Length: 23，body「HEAD, GET, OPTIONS, PUT」
resp.options_missing	对不存在的路径发 OPTIONS：HTTP 404，Content-Length: 0，body「」
resp.405	对只有 GET/PUT 的资源发 DELETE：HTTP 405，Allow: GET,OPTIONS,PUT，Content-Length: 0，body「」
resp.404	不存在的路径：HTTP 404，Content-Length: 0，body「」
exc.wae	抛 WebApplicationException(409)：HTTP 409，Content-Length: 5，body「taken」
exc.mapped	抛有映射器的异常：HTTP 422，Content-Length: 19，body「mapped:库存不足」
exc.mapper_broken	映射器自身抛异常：HTTP 500，Content-Length: 0，body「」
exc.unmapped	抛没有映射器的 RuntimeException：HTTP 500，Content-Length: 0，body「」
exc.no_writer	返回没有 MessageBodyWriter 的类型：HTTP 500，Content-Length: 0，body「」
param.int_missing	@QueryParam int 缺省：HTTP 200，Content-Length: 5，body「int=0」
param.boxed_missing	@QueryParam Integer 缺省：HTTP 200，Content-Length: 12，body「Integer=null」
param.default	@DefaultValue("5") 缺省：HTTP 200，Content-Length: 5，body「int=5」
param.int_empty	@QueryParam int 传空串 ?n=：HTTP 200，Content-Length: 5，body「int=0」
param.query_bad	@QueryParam int 传 abc：HTTP 404，Content-Length: 0，body「」
param.path_bad	@PathParam int 传 abc：HTTP 404，Content-Length: 0，body「」
param.header_bad	@HeaderParam int 传 abc：HTTP 400，Content-Length: 0，body「」
param.code	类型同时有 String 构造器、valueOf、fromString：HTTP 200，Content-Length: 10，body「valueOf(7)」
param.statics	只有 valueOf 与 fromString：HTTP 200，Content-Length: 10，body「valueOf(7)」
param.enum	枚举（自带 valueOf，另写 fromString），传 h：HTTP 200，Content-Length: 4，body「HIGH」
param.strict_query	@QueryParam 的转换构造器抛 IllegalArgumentException：HTTP 404，Content-Length: 0，body「」
param.strict_header	@HeaderParam 的转换构造器抛 IllegalArgumentException：HTTP 400，Content-Length: 0，body「」
param.list	@QueryParam List<Integer>，?x=1&x=2：HTTP 200，Content-Length: 11，body「list=[1, 2]」
param.list_bad	@QueryParam List<Integer>，?x=1&x=b：HTTP 404，Content-Length: 0，body「」
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
