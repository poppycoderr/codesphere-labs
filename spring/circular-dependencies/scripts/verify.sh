#!/usr/bin/env bash
# Spring 循环依赖：构造器、字段、prototype 循环；Framework 与 Boot 4 的默认值；@Transactional 与 @Async 参与的循环；@Lazy 与拆出第三个服务
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
grep -A16 "APPLICATION FAILED TO START" "$OUT/maven-test.log" | head -17 >"$OUT/boot-failure-analysis.txt" || true
grep -m1 "marked for pre-instantiation (not lazy-init) but currently initialized by other thread" "$OUT/maven-test.log" | sed -E 's/^\[[^]]+\] - [0-9]+ /<timestamp> /' >"$OUT/async-retry-log.txt" || true
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4} [0-9:]+ [AP]M /<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=3 failures=0" "3 个测试全部通过"
expect_line "$OUT/facts.tsv" "构造器循环，Spring Framework 默认：启动失败：BeanCurrentlyInCreationException" "构造器循环无法启动"
expect_line "$OUT/facts.tsv" "字段循环，Spring Framework 默认：启动成功" "Framework 默认允许字段循环"
expect_line "$OUT/facts.tsv" "字段循环，Spring Framework setAllowCircularReferences(false)：启动失败" "Framework 关闭后失败"
expect_line "$OUT/facts.tsv" "字段循环，Spring Boot 4.1.1 默认：启动失败：BeanCurrentlyInCreationException" "Boot 默认禁止字段循环"
expect_line "$OUT/facts.tsv" "字段循环，Spring Boot 4.1.1 + spring.main.allow-circular-references=true：启动成功" "Boot 打开开关后可以启动"
expect_line "$OUT/boot-failure-analysis.txt" "Relying upon circular references is discouraged and they are prohibited by default." "Boot 的失败说明"
expect_line "$OUT/facts.tsv" "prototype 字段循环，Spring Framework 默认：getBean 失败：BeanCurrentlyInCreationException" "prototype 循环失败"
expect_line "$OUT/facts.tsv" "容器里的 TxOrder 是代理=true，TxInventory 持有的也是同一个代理=true" "@Transactional 的循环拿到一致的代理"
expect_line "$OUT/facts.tsv" "容器启动时创建：启动成功；AsyncOrder 构造 2 次，AsyncInventory 构造 2 次；最终两边持有同一个代理=true" "@Async 的循环在启动时被重建一次"
expect_line "$OUT/async-retry-log.txt" "but currently initialized by other thread - skipping it in mainline thread" "重建前只留下一条 INFO 日志"
expect_line "$OUT/facts.tsv" "has been injected into other beans [labs.cycles.Cycles\$AsyncInventory] in its raw version as part of a circular reference, but has eventually been wrapped" "延迟初始化时 @Async 的循环直接失败"
expect_line "$OUT/facts.tsv" "注入的是代理=true，第一次调用时才解析，返回「inventory」" "@Lazy 打破构造器循环"
expect_line "$OUT/facts.tsv" "拆出 Checkout 依赖订单与库存、两者互不依赖，Spring Boot 4.1.1 默认：启动成功" "拆出第三个服务后无需开关"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.1" "spring-boot 4.1.1"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-beans:jar:7.0.9" "spring-beans 7.0.9"
log "全部通过，输出在 $OUT"
