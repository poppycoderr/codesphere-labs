#!/usr/bin/env bash
# Filter、HandlerInterceptor、@RestControllerAdvice 的执行顺序与能看到什么（Spring Boot 4.1.2，内嵌 Tomcat 11）
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3 
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[0-9-]+T[0-9:.+-]+ +/<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

f="$OUT/facts.tsv"
expect_line "$OUT/test-results.txt" "tests=5 failures=0" "5 个测试全部通过"
expect_line "$f" "ResponseEntity 返回字符串：状态 200；响应头 X-Filter-Before 有，X-Pre 有，X-Post 无，X-Filter-After 无；经过 filter-before → preHandle → handler → postHandle(committed=true) → afterCompletion(ex=null) → filter-after(committed=true)" "body.entity"
expect_line "$f" "@ResponseBody 返回对象：状态 200；响应头 X-Filter-Before 有，X-Pre 有，X-Post 有，X-Filter-After 有；经过 filter-before → preHandle → handler → postHandle(committed=false) → afterCompletion(ex=null) → filter-after(committed=false)" "body.json"
expect_line "$f" "Filter 里抛异常：状态 500，响应体是否来自 @RestControllerAdvice=false；经过 filter-before → preHandle → postHandle(committed=true) → afterCompletion(ex=null)" "filter.throws"
expect_line "$f" "处理器抛异常、@RestControllerAdvice 处理：状态 409，响应体 {\"error\":\"库存不足\"}；经过 filter-before → preHandle → handler → advice → afterCompletion(ex=null) → filter-after(committed=true)" "handler.throws"
expect_line "$f" "preHandle 返回 false：状态 200，响应体长度 0；经过 filter-before → preHandle → filter-after(committed=false)" "prehandle.false"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.2:compile" "spring-boot 4.1.2"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-webmvc:jar:7.0.10:compile" "spring-webmvc 7.0.10"
expect_line "$OUT/dependencies.txt" "org.apache.tomcat.embed:tomcat-embed-core:jar:11.0.27:compile" "tomcat-embed-core 11.0.27"
expect_line "$OUT/dependencies.txt" "tools.jackson.core:jackson-databind:jar:3.1.7:compile" "jackson-databind 3.1.7"
log "全部通过，输出在 $OUT"
