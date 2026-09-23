# 被 verify.sh source：启动拓扑、配置复制、执行 SQL 的函数
COMPOSE=(docker compose -f compose.yaml)
# on <节点> [mysql 参数...]：从标准输入读取 SQL（演示密码 example_password，只在本地容器中使用）
on() { local n="$1"; shift; "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password "$n" mysql -uroot "$@"; }
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
    on "$r" <<'SQL'
-- 容器初始化（创建账户等）在 replica 上留下了本地 GTID；建立复制前清掉，否则它们会成为 source 上不存在的 errant transaction
RESET BINARY LOGS AND GTIDS;
CHANGE REPLICATION SOURCE TO SOURCE_HOST = 'source-repl', SOURCE_USER = 'repl', SOURCE_PASSWORD = 'example_password',
  SOURCE_AUTO_POSITION = 1, SOURCE_SSL = 1, GET_SOURCE_PUBLIC_KEY = 1,
  SOURCE_CONNECT_RETRY = 5;  -- 默认 60 秒；缩短后网络恢复能更快重连
START REPLICA;
-- 容器初始化需要写入系统表，所以 replica 的只读在复制配置完成后再打开
SET PERSIST super_read_only = ON;
SQL
  done
  # 等两个 replica 追上 source
  local gtid; gtid=$(echo "SELECT @@global.gtid_executed;" | on source -N)
  for r in replica1 replica2; do echo "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', 60);" | on "$r" -N >/dev/null; done
}
