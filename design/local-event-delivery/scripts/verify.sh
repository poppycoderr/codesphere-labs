#!/usr/bin/env bash
# 本地事件的投递：同步与异步监听器的线程、顺序与异常，有界线程池的拒绝，提交后监听器的触发条件与失败
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
normalize_paths <"$OUT/maven-test.log" >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

grep -E "TransactionSynchronization|通知组织者失败" "$OUT/maven-test.log" | head -3 \
  | sed -E 's/^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4} [0-9:]+ [AP]M /<timestamp> /' >"$OUT/after-commit-log.txt" || true

expect_line "$OUT/test-results.txt" "tests=7 failures=0" "7 个测试全部通过"
expect_line "$OUT/facts.tsv" "sync.order_and_threads	points@publisher audit@publisher email@async-1 afterCommit@publisher" "同步监听器按 @Order 在发布者线程执行，异步监听器在线程池，提交后监听器在提交后执行"
expect_line "$OUT/facts.tsv" "sync.failure	发布者收到 审计写入失败；报名已提交 0 条；提交后监听器调用 0 次" "同步监听器失败让报名回滚"
expect_line "$OUT/facts.tsv" "异步异常只进入 AsyncUncaughtExceptionHandler（1 次）" "异步监听器失败不影响发布者"
expect_line "$OUT/facts.tsv" "连续发布 10 个事件，3 个被接受，7 个在发布者线程抛出 TaskRejectedException；被拒绝的报名已经保存 10 条" "有界线程池在发布者线程拒绝，报名与事件分叉"
expect_line "$OUT/facts.tsv" "没有事务时发布：提交后监听器调用 0 次" "没有事务时提交后监听器不执行"
expect_line "$OUT/facts.tsv" "提交后监听器抛异常：报名已提交 1 条；发布者没有收到异常" "提交后监听器的异常不回到发布者"
expect_line "$OUT/after-commit-log.txt" "SEVERE: TransactionSynchronization.afterCompletion threw exception" "异常只在日志里出现一次 SEVERE"
expect_line "$OUT/facts.tsv" "CGLIB 代理，代理对象上的 calls 字段为 null" "带 @Async 的 Bean 是 CGLIB 代理，字段不能直接访问"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-tx:jar:7.0.9" "spring-tx 7.0.9"
log "全部通过，输出在 $OUT"
