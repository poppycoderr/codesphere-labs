#!/usr/bin/env bash
# readOnly 与 timeout 的实际行为（Spring Boot 4.1.2 + JPA，MySQL 8.4.11 与 H2 对照）
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3 docker
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "mysql_image: mysql:8.4.11@sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a"
normalize_paths <"$OUT/maven-test.log" | sed -E 's/^[0-9-]+T[0-9:.+-]+ +/<timestamp> /' >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

f="$OUT/facts.tsv"
expect_line "$OUT/test-results.txt" "tests=6 failures=0" "6 个测试全部通过"
expect_line "$f" "H2：同样在 readOnly 事务里用 JdbcTemplate 插入：成功；表里 id=2 的行数 1" "h2.readonly.jdbc"
expect_line "$f" "MySQL：readOnly 事务里用 JdbcTemplate 插入：TransientDataAccessResourceException，根因 SQLException：Connection is read-only. Queries leading to data modification are not allowed；表里 id=2 的行数 0" "mysql.readonly.jdbc"
expect_line "$f" "MySQL：readOnly 事务里修改已加载的实体：没有报错，数据库里仍是「原始名字」；再显式调用 save：没有报错，仍是「原始名字」；读写事务里同样的修改：变成「读写事务改的名字」" "mysql.readonly.jpa"
expect_line "$f" "timeout = 1 秒：插入后在 Java 里再耗时 1.5 秒：方法正常返回（耗时超过 1 秒=true），提交了 1 行" "mysql.timeout.after"
expect_line "$f" "timeout = 1 秒：先在 Java 里耗时 1.5 秒再执行插入：TransactionTimedOutException（在创建语句时检查剩余时间），提交了 0 行" "mysql.timeout.before"
expect_line "$f" "timeout = 1 秒：一条 SELECT SLEEP(3)：QueryTimeoutException（MySQLTimeoutException），在 1—2 秒之间结束=true（剩余时间被设成了语句的查询超时）" "mysql.timeout.statement"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.2:compile" "spring-boot 4.1.2"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-tx:jar:7.0.10:compile" "spring-tx 7.0.10"
expect_line "$OUT/dependencies.txt" "org.hibernate.orm:hibernate-core:jar:7.4.13.Final:compile" "hibernate-core 7.4.13.Final"
expect_line "$OUT/dependencies.txt" "com.mysql:mysql-connector-j:jar:9.7.0:runtime" "mysql-connector-j 9.7.0"
expect_line "$OUT/dependencies.txt" "com.zaxxer:HikariCP:jar:7.0.2:compile" "HikariCP 7.0.2"
log "全部通过，输出在 $OUT"
