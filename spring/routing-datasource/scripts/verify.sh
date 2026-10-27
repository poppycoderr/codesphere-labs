#!/usr/bin/env bash
# AbstractRoutingDataSource 的选库时机、延迟取连接、只读路由与嵌套作用域（Spring Boot 4.1.2 + 两个 H2 库）
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
expect_line "$f" "用 hash & (桶数 − 1) 取桶：6 个桶时只用到了 [0, 1, 4, 5]，8 个桶时用到了 [0, 1, 2, 3, 4, 5, 6, 7]（位与取模要求桶数是 2 的幂）" "bucket.mask"
expect_line "$f" "外面包一层 LazyConnectionDataSourceProxy，同样在事务内部、第一条语句之前设置：落到 replica（连接推迟到第一条语句才取）" "key.lazy"
expect_line "$f" "外层设为 replica（第一次查询落到 replica），中途调用的方法临时切到 primary 后 remove：外层下一次查询落到 primary；改成保存并恢复：落到 replica" "key.nested"
expect_line "$f" "设置路由键为 replica：在事务外查询落到 replica；在事务方法内部设置后查询落到 primary；在进入事务之前设置落到 replica" "key.timing"
expect_line "$f" "按 isCurrentTransactionReadOnly() 路由只读事务：直接用路由数据源落到 primary（取连接时只读标志还没设上）；包一层 LazyConnectionDataSourceProxy 落到 replica" "readonly.route"
expect_line "$OUT/dependencies.txt" "org.springframework.boot:spring-boot:jar:4.1.2:compile" "spring-boot 4.1.2"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-jdbc:jar:7.0.10:compile" "spring-jdbc 7.0.10"
log "全部通过，输出在 $OUT"
