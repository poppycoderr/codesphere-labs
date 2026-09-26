#!/usr/bin/env bash
# Spring 事务传播：REQUIRED 吞异常、noRollbackFor、NESTED 保存点、REQUIRES_NEW 的独立提交/连接池耗尽/同行锁等待、rollbackOn、AFTER_COMMIT
# Spring Boot 4.1.1 + MySQL 8.4.11（Testcontainers，固定 digest）；连接池 2 个连接，锁等待超时 2 秒
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3 docker
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test，需要 Docker）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "mysql_image: mysql:8.4.11@sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a"
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[0-9-]+T[0-9:.+-]+ +/<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

f="$OUT/facts.tsv"
expect_line "$OUT/test-results.txt" "tests=9 failures=0" "9 个测试全部通过"
expect_line "$f" "外层吞掉内层异常：抛出 UnexpectedRollbackException，订单行数 0" "REQUIRED 内层失败后外层提交得到 UnexpectedRollbackException"
expect_line "$f" "内层 noRollbackFor：外层正常提交，订单行数 1" "noRollbackFor 不标记 rollback-only"
expect_line "$f" "NESTED：第 2 行失败回滚到保存点，提交后留下 [1, 3]" "NESTED 只回滚失败的一行"
expect_line "$f" "同样的批次改用 REQUIRED：UnexpectedRollbackException，留下 0 行" "REQUIRED 下整批回滚"
expect_line "$f" "订单回滚后：订单行数 0，REQUIRES_NEW 写入的审计行数 1" "REQUIRES_NEW 独立提交"
expect_line "$f" "两个 REQUIRES_NEW 都失败 [CannotCreateTransactionException <- SQLTransientConnectionException, CannotCreateTransactionException <- SQLTransientConnectionException]" "连接池占满时 REQUIRES_NEW 拿不到连接"
expect_line "$f" "等待了连接超时（1 秒）之后才失败" "等满连接超时才失败"
expect_line "$f" "CannotAcquireLockException，根因 Lock wait timeout exceeded" "REQUIRES_NEW 等外层的行锁直到超时"
expect_line "$f" "默认规则下抛出受检异常：事务提交，订单行数 1" "受检异常默认提交"
expect_line "$f" "rollbackOn = ALL_EXCEPTIONS 时抛出受检异常：事务回滚，订单行数 0" "rollbackOn 让受检异常回滚"
expect_line "$f" "AFTER_COMMIT 监听器：提交的订单 5 收到，回滚的订单 6 没有收到" "AFTER_COMMIT 只在提交后执行"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.1" "spring-boot 4.1.1"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-tx:jar:7.0.9" "spring-tx 7.0.9"
log "全部通过，输出在 $OUT"
