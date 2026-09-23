# 被 verify.sh source：启动拓扑、配置复制与半同步、故障注入与提升
COMPOSE=(docker compose -f compose.yaml)
NET_REPL=csl-failover_repl
# on <节点> [mysql 参数...]：从标准输入读取 SQL（演示密码 example_password，只在本地容器中使用）
on() { local n="$1"; shift; "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password "$n" mysql -uroot "$@"; }
cid() { "${COMPOSE[@]}" ps -aq "$1"; }
utc() { python3 -c "import datetime; print(datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds'))"; }
# ev <场景> <事件> [说明]：追加一条时间线事件
ev() { printf '%s\t%s\t%s\n' "$2" "$(utc)" "${3:-}" >>"$OUT/$1-timeline.tsv"; }

replicate_from() {  # replicate_from <replica> <source 主机名>
  on "$1" <<SQL
CHANGE REPLICATION SOURCE TO SOURCE_HOST = '$2', SOURCE_USER = 'repl', SOURCE_PASSWORD = 'example_password',
  SOURCE_AUTO_POSITION = 1, SOURCE_SSL = 1, GET_SOURCE_PUBLIC_KEY = 1, SOURCE_CONNECT_RETRY = 2;
START REPLICA;
SQL
}
topology_up() {
  "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
  "${COMPOSE[@]}" up -d --wait >&2
  on source <<'SQL'
CREATE USER 'repl'@'%' IDENTIFIED BY 'example_password';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
CREATE DATABASE labs;
SQL
  local r
  for r in replica1 replica2; do
    # 容器初始化在 replica 上留下的本地 GTID 会成为 errant transaction，建立复制前清掉
    echo "RESET BINARY LOGS AND GTIDS;" | on "$r"
    replicate_from "$r" source-repl
    echo "SET PERSIST super_read_only = ON;" | on "$r"
  done
}
semisync_on() {  # semisync_on <超时毫秒>
  echo "INSTALL PLUGIN rpl_semi_sync_source SONAME 'semisync_source.so';
        SET GLOBAL rpl_semi_sync_source_enabled = 1; SET GLOBAL rpl_semi_sync_source_timeout = $1;" | on source
  local r
  for r in replica1 replica2; do
    # INSTALL PLUGIN 会写 mysql.plugin 表，暂时关闭只读
    echo "SET GLOBAL super_read_only = OFF; INSTALL PLUGIN rpl_semi_sync_replica SONAME 'semisync_replica.so'; SET GLOBAL super_read_only = ON;
          SET GLOBAL rpl_semi_sync_replica_enabled = 1;
          STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;" | on "$r"
  done
  sleep 2
}
wait_synced() {
  local gtid r; gtid=$(echo "SELECT @@global.gtid_executed;" | on source -N)
  for r in replica1 replica2; do echo "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', 60);" | on "$r" -N >/dev/null; done
}
# detect <场景>：从 replica1 每秒探测一次 source，连续 3 次失败判定故障
detect() {
  local fails=0
  while [ "$fails" -lt 3 ]; do
    if "${COMPOSE[@]}" exec -T replica1 mysqladmin -h source -uroot -pexample_password --connect-timeout=1 ping >/dev/null 2>&1; then fails=0; else fails=$((fails + 1)); fi
    sleep 1
  done
  ev "$1" detected "连续 3 次探测失败（间隔 1 秒）"
}
# candidates <场景>：记录每个 replica 的连接状态、已接收与已执行 GTID 集合
candidates() {
  local r
  for r in replica1 replica2; do
    echo "== $r"
    echo "SELECT c.SERVICE_STATE, c.RECEIVED_TRANSACTION_SET, @@global.gtid_executed AS executed,
                 GTID_SUBTRACT(c.RECEIVED_TRANSACTION_SET, @@global.gtid_executed) AS received_not_applied,
                 (SELECT COUNT(*) FROM labs.orders) AS rows_in_orders, (SELECT MAX(seq) FROM labs.orders) AS max_seq
          FROM performance_schema.replication_connection_status c\G" | on "$r"
  done >"$OUT/$1-candidates.txt"
}
# promote <场景> <候选>：停止接收，先把已接收的 relay log 全部应用，再提升为可写的 source
promote() {
  ev "$1" promotion_started "candidate=$2"
  local received
  received=$(echo "STOP REPLICA IO_THREAD; SELECT RECEIVED_TRANSACTION_SET FROM performance_schema.replication_connection_status;" | on "$2" -N | tr -d '\n')
  echo "START REPLICA SQL_THREAD; SELECT WAIT_FOR_EXECUTED_GTID_SET('$received', 120);" | on "$2" -N >/dev/null
  ev "$1" relay_log_applied "candidate=$2"
  echo "STOP REPLICA; RESET REPLICA ALL; SET PERSIST super_read_only = OFF; SET PERSIST read_only = OFF;" | on "$2"
  ev "$1" promotion_done "candidate=$2 executed=$(echo "SELECT @@global.gtid_executed;" | on "$2" -N | tr -d '\n')"
}
