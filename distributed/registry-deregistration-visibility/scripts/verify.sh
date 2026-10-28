#!/usr/bin/env bash
# 实例下线后各层何时不再可见：正常停机、崩溃、先注销再停服务（Spring Boot 4.0.9 + Spring Cloud 2025.1.4，Eureka Server 与 LoadBalancer 同进程）
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
cp target/timings.txt "$OUT/timings.txt"
expect_line "$OUT/test-results.txt" "tests=1 failures=0" "测试通过"
expect_line "$f" "崩溃：失败的请求比正常停机多=true" "abrupt.errors"
expect_line "$f" "崩溃（不注销、靠心跳过期）：B 从注册表消失比正常停机晚 2 秒以上=true" "abrupt.later"
expect_line "$f" "崩溃：同样按注册中心、本地副本、负载均衡器的顺序传播=true" "abrupt.order"
expect_line "$f" "正常停机直接关闭：B 的端口已关闭、调用方仍在选择它，期间出现失败的请求=true" "graceful.errors"
expect_line "$f" "正常停机：注册中心（注册表与接口）先变，调用方本地副本其次，负载均衡器最后才停止选择 B=true" "graceful.order"
expect_line "$f" "先注销、等待 16 秒、再停服务：失败的请求 0 个" "ordered.errors"
expect_line "$f" "先注销之后，B 仍然收到过请求（还在服务，所以没有失败）=true" "ordered.hits"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.0.9:compile" "spring-boot 4.0.9"
expect_line "$OUT/dependencies.txt" "org.springframework.cloud:spring-cloud-netflix-eureka-server:jar:5.0.3:compile" "eureka-server 5.0.3"
expect_line "$OUT/dependencies.txt" "org.springframework.cloud:spring-cloud-loadbalancer:jar:5.0.4:compile" "loadbalancer 5.0.4"
log "全部通过，输出在 $OUT"
