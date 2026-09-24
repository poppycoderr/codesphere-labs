#!/usr/bin/env bash
# 分层与依赖规则：主代码满足四层规则，三个违规夹具被拦下；一条报名用例穿过四层的顺序与三种失败；Session ↔ SessionRecord 往返检查
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
mvn -B -q -Dstyle.color=never dependency:list -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=7 failures=0" "7 个测试全部通过"
expect_line "$OUT/facts.tsv" "3 条规则全部通过" "主代码满足三条架构规则"
expect_line "$OUT/facts.tsv" "arch.violation.domainleak	被 LAYERS、DOMAIN_FREE_OF_OUTER_LAYERS 拦下；Field <labs.ddd.violations.domainleak.domain.Session.state> has type <labs.ddd.violations.domainleak.infrastructure.SessionPO>" "领域对象持有 PO 被拦下"
expect_line "$OUT/facts.tsv" "arch.violation.framework	被 DOMAIN_FREE_OF_FRAMEWORKS 拦下；Class <labs.ddd.violations.framework.domain.Session> is annotated with <org.springframework.stereotype.Component>" "领域对象带 Spring 注解被拦下"
expect_line "$OUT/facts.tsv" "arch.violation.bypass	被 LAYERS 拦下" "适配器绕过应用层直接调用仓储被拦下"
expect_line "$OUT/facts.tsv" "adapter:收到 POST /sessions/S-1/registrations → infrastructure:BEGIN → application:开始用例 RegisterForSession → infrastructure:SELECT session S-1 → domain:Session.register 通过容量与重复检查 → infrastructure:UPDATE session ... WHERE version=3：1 行 → infrastructure:COMMIT → infrastructure:发布 RegistrationConfirmed → adapter:返回 201 CONFIRMED" "成功路径：事件在提交之后发布"
expect_line "$OUT/facts.tsv" "trace.bad_phone	400 手机号格式不正确；经过的层：adapter,application" "非法手机号在命令转换时被拒绝，没有开启事务"
expect_line "$OUT/facts.tsv" "ROLLBACK（RegistrationRefused） → 返回 422 DUPLICATE" "重复报名由领域拒绝，事务回滚"
expect_line "$OUT/facts.tsv" "409 请重试；ROLLBACK（ConcurrentModification） → 返回 409 请重试；新发布事件 0 个" "版本冲突回滚，不发布事件"
expect_regex "$OUT/facts.tsv" "完整转换器不一致 0 个；漏掉候补表的转换器不一致 ([1-9][0-9]*) 个（等于有候补的场次数 \1）" "往返检查发现漏映射的字段"
expect_line "$OUT/dependencies.txt" "com.tngtech.archunit:archunit:jar:1.5.0" "ArchUnit 1.5.0"
log "全部通过，输出在 $OUT"
