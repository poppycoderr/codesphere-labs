#!/usr/bin/env bash
# 运行中改配置：Spring 的 @Value 字段与派生对象、ThreadPoolExecutor 的最大与核心线程数、Logback 显式配置过级别的 logger、HikariCP 启动后封住的参数
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="org.springframework:spring-context:7.0.9 org.springframework:spring-core:7.0.9 org.springframework:spring-beans:7.0.9 org.springframework:spring-aop:7.0.9 org.springframework:spring-expression:7.0.9 io.micrometer:micrometer-observation:1.16.7 io.micrometer:micrometer-commons:1.16.7 commons-logging:commons-logging:1.3.5 ch.qos.logback:logback-classic:1.5.38 ch.qos.logback:logback-core:1.5.38 org.slf4j:slf4j-api:2.0.20 com.zaxxer:HikariCP:7.0.2 com.mysql:mysql-connector-j:9.7.0"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/ConfigLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 spring=7.0.9
spring.environment	属性源里的 limit.qps 从 100 改成 500 之后：Environment.getProperty 读到 500
spring.value_field	启动时注入的 @Value 字段：100
spring.derived	启动时用这个值算出来的对象（每次间隔的纳秒数）：10000000（按 500 应为 2000000）
spring.new_bean	此后新创建的 Bean 注入到的值：500
pool.before	核心 4、最大 4、无界队列，提交 20 个阻塞任务：核心 4，最大 4，实际线程 4，排队 16
pool.max_only	把最大线程数改成 16：核心 4，最大 16，实际线程 4，排队 16
pool.core_over_max	另一个池（核心 4、最大 4）先把核心线程数改成 16：抛出 IllegalArgumentException（corePoolSize must be less than or equal to maximumPoolSize）
pool.core_raised	最大线程数已是 16，再把核心线程数改成 16：核心 16，最大 16，实际线程 16，排队 4
pool.core_lowered_busy	任务还在执行时把核心线程数改回 4：核心 4，最大 16，实际线程 16，排队 4
pool.core_lowered_idle	任务全部结束、空闲超过保活时间之后：核心 4，最大 16，实际线程 4，排队 0
pool.bounded_not_full	核心 4、队列容量 10、已有 5 个在排队，把最大线程数从 4 改成 8：核心 4，最大 8，实际线程 4，排队 5
pool.bounded_full	再提交 7 个任务（队列满了之后才会加线程）：核心 4，最大 8，实际线程 6，排队 10
logback.root_changed	运行中把 root 从 INFO 调成 DEBUG：com.shop.order.OrderService 的生效级别 DEBUG，com.shop.pay.PayService 的生效级别 WARN
hikari.max_pool_size	连接池启动后 setMaximumPoolSize(30)：成功，getMaximumPoolSize = 30
hikari.jdbc_url	连接池启动后 setJdbcUrl(新地址)：抛出 IllegalStateException（The configuration of the pool is sealed once started. Use HikariConfigMXBean for runtime changes.）
hikari.password	连接池启动后 setPassword(新口令)：成功（只影响之后新建的连接）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
