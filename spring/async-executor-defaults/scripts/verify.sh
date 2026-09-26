#!/usr/bin/env bash
# Spring Boot 4.1.1 默认的异步执行器：队列无界、线程数停在核心线程数；开启虚拟线程后换成 SimpleAsyncTaskExecutor
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
expect_line "$OUT/test-results.txt" "tests=2 failures=0" "2 个测试全部通过"
expect_line "$f" "applicationTaskExecutor=ThreadPoolTaskExecutor，corePoolSize=8，maxPoolSize=2147483647，队列剩余容量=2147483647" "默认执行器：核心 8、最大与队列都是 Integer.MAX_VALUE"
expect_line "$f" "提交 50 个卡住的 @Async 任务：线程数 8，排队 42，同时在运行的任务 8 个，执行线程 platform" "队列无界时线程数停在 8"
expect_line "$f" "applicationTaskExecutor=SimpleAsyncTaskExecutor，@Async 方法运行在 virtual 线程上，同时在运行的任务 50 个" "开启虚拟线程后每个任务一个虚拟线程，没有上限"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.1" "spring-boot 4.1.1"
log "全部通过，输出在 $OUT"
