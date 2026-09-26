#!/usr/bin/env bash
# Spring Boot 自动配置：四类条件、条件报告、自动配置之间的顺序、不在扫描路径里的自动配置经 imports 文件加载
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
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4} [0-9:]+ [AP]M /<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=3 failures=0" "3 个测试全部通过"
expect_line "$OUT/facts.tsv" "默认：默认 HTTP 客户端 → https://pay.example.test（sdk-3.2）" "条件都满足时使用默认实现"
expect_line "$OUT/facts.tsv" "用户声明了 PaymentClient：容器里 1 个，是「用户自己的客户端」" "用户的 Bean 让默认实现退让"
expect_line "$OUT/facts.tsv" "payment.enabled=false：PaymentClient 0 个；条件报告：@ConditionalOnProperty (payment.enabled=true) found different value in property 'enabled'" "属性关闭"
expect_line "$OUT/facts.tsv" "classpath 上没有 PaymentSdk：PaymentClient 0 个；条件报告：@ConditionalOnClass did not find required class 'labs.payment.sdk.PaymentSdk'" "类不在 classpath 上"
expect_line "$OUT/facts.tsv" "兜底配置没有声明 after：MeterSink 2 个 [noop, prometheus]" "没有声明顺序时兜底配置也生效，出现两个 Bean"
expect_line "$OUT/facts.tsv" "兜底配置声明 after = MetricsAutoConfiguration：MeterSink 1 个 [prometheus]" "声明顺序后兜底配置正确退让"
expect_line "$OUT/facts.tsv" "PaymentClient 存在=true；不在 imports 文件里的 labs.payment.internal.InternalConfig 生效=false" "自动配置经 imports 文件加载，与组件扫描无关"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot-autoconfigure:jar:4.1.1" "spring-boot-autoconfigure 4.1.1"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-context:jar:7.0.9" "spring-context 7.0.9"
log "全部通过，输出在 $OUT"
