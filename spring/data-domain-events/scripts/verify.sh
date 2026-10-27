#!/usr/bin/env bash
# Spring Data 领域事件的发布时机与清空、PO 构造器登记事件、AFTER_COMMIT 监听器里的写入（Spring Boot 4.1.2 + H2）
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
expect_line "$OUT/test-results.txt" "tests=6 failures=0" "6 个测试全部通过"
expect_line "$f" "不开新事务的那次写入：被重置 autoCommit 的动作一并提交了（取决于连接清理顺序，不是契约）" "after-commit.dirty"
expect_line "$f" "连接池 auto-commit=false 时同样的两种写法：不开新事务查到 0 行，REQUIRES_NEW 查到 1 行" "after-commit.pool-off"
expect_line "$f" "AFTER_COMMIT 监听器里用 JdbcTemplate 插入一行：不开新事务时方法返回后查到 1 行；监听器标注 REQUIRES_NEW 时查到 1 行" "after-commit.write"
expect_line "$f" "PO 构造器里登记「已申请」，每次保存都 new 一个 PO：申请 1 次、关闭 1 次后，TicketApplied 发布 2 次，TicketClosed 0 次（关闭工单被当成又申请了一次）" "po.constructor"
expect_line "$f" "事件由领域行为产生、映射时带过去：TicketApplied 1 次，TicketClosed 1 次" "po.domain"
expect_line "$f" "close() 登记了 TicketClosed，但依赖脏检查没有调用 save：数据库里状态是 CLOSED，事件发布 0 次；显式调用 save：发布 1 次，提交后监听器收到 1 次" "save.dirty-checking"
expect_line "$f" "只有 @DomainEvents、没有 @AfterDomainEventPublication：同一个对象 save 三次，TicketOpened 发布 3 次" "save.no-clear"
expect_line "$f" "save 一个登记了 TicketOpened 的新聚合：发布 1 次，监听器收到时 （事务内）；没有新事件再 save 一次：累计仍是 1 次（发布后已清空）" "save.publish"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.2:compile" "spring-boot 4.1.2"
expect_line "$OUT/dependencies.txt" "org.springframework.data:spring-data-commons:jar:4.1.2:compile" "spring-data-commons 4.1.2"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-tx:jar:7.0.10:compile" "spring-tx 7.0.10"
expect_line "$OUT/dependencies.txt" "org.hibernate.orm:hibernate-core:jar:7.4.13.Final:compile" "hibernate-core 7.4.13.Final"
log "全部通过，输出在 $OUT"
