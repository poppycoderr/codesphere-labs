#!/usr/bin/env bash
# 未读数的两种存法：计数器与已读位置，在重复投递、清零与新消息交错、多端上报乱序、提交顺序与自增 ID 顺序不一致、群聊写放大下的结果
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 25 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
JDK_IMAGE="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
JDBC="com.mysql:mysql-connector-j:9.7.0"
PROJECT=csl-unread-count-watermark
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
DRIVER=$(maven_jar "$JDBC")
f="$OUT/output.tsv"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/UnreadLab.java 2>/dev/null >"$f"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $JDBC"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $JDBC"
cat "$f" >&2
# 两条连接按固定顺序交替执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 mysql=8.4.11 isolation=REPEATABLE-READ
retry.counter	alice 发了 10 条，其中 3 条的通知被重复投递：计数器 = 13
retry.watermark	同样的投递，按已读位置现算：未读 = 10
clear_race.counter	bob 看完前 10 条后上报已读，请求到达前第 11 条已写入：计数器清零后 = 0，实际没看过的有 1 条
clear_race.watermark	上报的是「读到第 10 条」：未读 = 1
devices.overwrite	手机上报读到第 20 条，随后电脑上一个迟到的请求上报第 12 条，直接覆盖：未读 = 8
devices.greatest	写成 last_read_id = GREATEST(last_read_id, ?)：未读 = 0
own_messages	alice 自己发了 20 条、从没上报过已读：不排除自己发的 = 20，排除后 = 0
commit_order.ids	事务 A 先插入（ID 排第 1）未提交，事务 B 后插入（ID 排第 2）先提交；A 的 ID 小于 B 的 ID = true
commit_order.missed	carol 此时拉取，只看到 B 的那条，已读位置记到 B 的 ID；A 提交之后：未读 = 0，而会话里 ID 不大于已读位置的消息有 2 条，carol 只看过 1 条
commit_order.gap	一个事务插入后回滚，下一条消息的 ID 与上一条已提交消息的 ID 相差 2（回滚的那个 ID 是其间的 1 号位，不再出现）
conv_seq.blocked	事务 A 锁住会话行分配序号、尚未提交时，事务 B 来取序号：等待 1 秒后超时（Lock wait timeout exceeded; try restarting transaction）
conv_seq.order	A 提交后 B 重试：A 的序号 1，B 的序号 2；序号小的一定先提交
fanout.counter	1000 人的群里发一条消息，计数器方式要更新 999 行；已读位置方式要更新 0 行（未读数在读的时候算）
fanout.capped	m1 有 5000 条未读：完整计数 = 5000；只数到 100 就停（界面显示 99+）= 100
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
