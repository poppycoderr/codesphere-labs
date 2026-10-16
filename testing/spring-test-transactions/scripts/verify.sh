#!/usr/bin/env bash
# Spring 测试事务的失真：唯一约束推迟到 flush、懒加载在测试事务里总能读到、提交后回调不执行、另一个线程看不到未提交的数据、REQUIRES_NEW 的写入不随回滚撤销、替换 Bean 导致新建上下文（Spring Boot 4.1.1、H2）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 + temurin 25 容器（下载依赖、运行测试）与 temurin 25 容器（汇总报告）；约 2 分钟（首次需下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
rm -rf target
# 有些测试按设计会失败，用来展示失败信息；测试失败不中断构建，结果由下面的报告逐条比较
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B test -Dmaven.test.failure.ignore=true >"$OUT/maven.log" 2>&1 || fail "Maven 执行失败，见 $OUT/maven.log"
f="$OUT/output.tsv"
{ echo "env	java.version=25.0.4.1 spring-boot=4.1.1"; docker run --rm -v "$PWD:/w" -w /w "$J25" java src/Report.java 3; } >"$f"
write_environment "$OUT/environment.txt" "maven_image: $MVN" "jdk25_image: $J25" "spring-boot: 4.1.1;h2: 2.4.240"
cat "$f" >&2
# 测试结果与失败信息是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 spring-boot=4.1.1
A_TransactionalTests.a1_sameEmailTwice_noError	通过
A_TransactionalTests.a2_linesReadableAfterLoad	通过
A_TransactionalTests.a3_mailSentAfterRegister	失败：Expecting CopyOnWriteArrayList: ⏎ [] ⏎ to contain:
A_TransactionalTests.a4_orderVisibleToAnotherThread	失败：Expecting value to be true but was false
B_CommittedTests.b0_auditTableEmptyAtStart	失败：[前一个测试类回滚之后，审计表里的行数] ⏎ expected: 0L ⏎ but was: 3L
B_CommittedTests.b1_sameEmailTwice_noError	失败：Expecting code not to raise a throwable but caught ⏎ "org.springframework.dao.DataIntegrityViolationException: could not execute statement [Unique index or primary key violation: "PUBLIC.CONSTRAINT_87 INDEX PUBLIC.CONSTRAINT_INDEX_8 ON PUBLIC.MEMBER(EMAIL NULLS FIRST) VALUES ( /* 1 */ 'dup-b@example.com' )"; SQL statement: ⏎ insert into member (email,id) values (?,?) [23505-240]] [insert into member (email,id) values (?,?)]; SQL [insert into member (email,id) values (?,?)]; constraint [PUBLIC.CONSTRAINT_87 INDEX PUBLIC.CONSTRAINT_INDEX_8]
B_CommittedTests.b2_linesReadableAfterLoad	错误：Cannot lazily initialize collection of role 'lab.App$PurchaseOrder.lines' with key 'o-b' (no session)
B_CommittedTests.b3_mailSentAfterRegister	通过
B_CommittedTests.b4_orderVisibleToAnotherThread	通过
C_MockedBeanTests.c1_contextStarts	通过
Z_ContextCountTest.z1_record	通过
note.contexts	A、B、Z 三个测试类配置相同，C 替换了一个 Bean；整个测试过程中创建的应用上下文：2 个
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
