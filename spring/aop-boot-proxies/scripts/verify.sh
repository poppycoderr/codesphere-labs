#!/usr/bin/env bash
# Spring Boot 下的代理：默认 CGLIB、@Cacheable 与 @Async 的自调用、缓存与事务切面的顺序（H2 内存库）
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
expect_line "$OUT/test-results.txt" "tests=9 failures=0" "9 个测试全部通过"
expect_line "$f" "Spring Boot 默认：按实现类 OrderService 取 Bean 成功，CGLIB 代理=true，JDK 代理=false" "Boot 默认用 CGLIB，按实现类取 Bean 成功"
expect_line "$f" "@Cacheable 自调用两次：方法体执行 2 次；从外部调用两次：执行 1 次" "@Cacheable 自调用绕过缓存"
expect_line "$f" "@Async 从外部调用：运行在 task-N 线程；自调用：运行在调用方线程 main" "@Async 自调用在调用方线程同步执行"
expect_line "$f" "cache-outer：缓存命中 10 次，开启物理事务 0 次" "缓存在外层：命中时不开事务"
expect_line "$f" "tx-outer：缓存命中 10 次，开启物理事务 10 次" "事务在外层：每次命中都开一个事务"
expect_line "$f" "cache-outer：第一次调用回滚，表里 id=7 的行数 0；第二次调用再次抛出 UnexpectedRollbackException" "缓存在外层：回滚的结果不进缓存"
expect_line "$f" "tx-outer：第一次调用回滚，表里 id=7 的行数 0；第二次调用返回「new-7」" "事务在外层：回滚的结果留在缓存里"
expect_line "$f" "default：缓存命中 10 次，开启物理事务 0 次" "默认顺序下本实验的表现与缓存在外层相同"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.1" "spring-boot 4.1.1"
log "全部通过，输出在 $OUT"
