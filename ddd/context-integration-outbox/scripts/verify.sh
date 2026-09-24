#!/usr/bin/env bash
# 上下文集成：双写与 outbox、转发器崩溃后的重复投递与去重、乱序到达与版本判断、毒消息与死信、契约的新增与改名
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 21 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-ddd-outbox
GSON_COORD="com.google.code.gson:gson:2.11.0"
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
GSON=$(maven_jar "$GSON_COORD")
log "运行 src/Integration.java"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER"):/cache/m2/$(basename "$GSON")" src/Integration.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
{ echo "## reg.outbox（最后一个场景：毒消息）"; echo "SELECT status, COUNT(*) AS n, MAX(attempts) AS max_attempts FROM outbox GROUP BY status ORDER BY status;" | mysql_exec "$PROJECT" --default-character-set=utf8mb4 reg -t
  echo "## billing.dead_letter"; echo "SELECT type, error FROM dead_letter;" | mysql_exec "$PROJECT" --default-character-set=utf8mb4 billing -t
  echo "## 一条 outbox 记录的 payload"; echo "SELECT payload FROM outbox WHERE status='SENT' ORDER BY id LIMIT 1;" | mysql_exec "$PROJECT" --default-character-set=utf8mb4 reg -t
} | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<uuid>/g' >"$OUT/final-tables.txt"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $MYSQL_JDBC_JAR_COORD"; echo "gson: $GSON_COORD"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，2 CPU、1 GB）" "jdbc: $MYSQL_JDBC_JAR_COORD" "gson: $GSON_COORD"

expect_line "$OUT/output.tsv" "每 10 次在调用前崩溃 1 次：报名 100 条，应收 90 条" "双写：提交后崩溃丢失 10 笔应收"
expect_line "$OUT/output.tsv" "另有 1 笔在提交前失败（已回滚）：报名 100 条，outbox 待发送 100 条；转发器处理后应收 100 条" "outbox：业务与事件同一事务，全部送达"
expect_line "$OUT/output.tsv" "relay.no-inbox	50 个事件，第 3 批投递后、标记前崩溃；重启后继续：计费处理 60 次，应收 60 条，去重跳过 0 次" "没有去重：重复投递产生 10 笔多余应收"
expect_line "$OUT/output.tsv" "relay.inbox	50 个事件，第 3 批投递后、标记前崩溃；重启后继续：计费处理 60 次，应收 50 条，去重跳过 10 次" "按 eventId 去重：应收 50 条"
expect_line "$OUT/output.tsv" "order.naive	20 笔报名先确认后取消，取消事件先到达：应收 OPEN 20 条、VOID 0 条" "不判断版本：取消被丢弃，应收全部错误地保持 OPEN"
expect_line "$OUT/output.tsv" "order.versioned	20 笔报名先确认后取消，取消事件先到达：应收 OPEN 0 条、VOID 20 条，忽略过期事件 20 次" "按聚合版本判断：最终全部 VOID"
expect_line "$OUT/output.tsv" "应收 30 条，死信 1 条（字段 feeCents 不是整数：\"19.9 元\"），outbox FAILED 1 条、仍待发送 0 条" "毒消息 3 次后进入死信，不阻塞其他事件"
expect_line "$OUT/output.tsv" "生产者新增字段 channel：计费侧翻译通过" "新增字段：宽容读取"
expect_line "$OUT/output.tsv" "生产者把 feeCents 改名为 amountCents：契约测试失败：缺少字段 feeCents" "改名字段：契约测试在发布前失败"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
