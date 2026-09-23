#!/usr/bin/env bash
# 崩溃恢复：未提交与已提交事务在 kill -9 之后的结果；默认持久化参数；刷盘参数调低后的丢失与 binlog 分叉
# 故障模型只覆盖「mysqld 进程被 SIGKILL」，宿主机掉电与存储写缓存丢失无法在容器中模拟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"
COMPOSE=(docker compose -f compose.yaml)
sq() { "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password mysql mysql -uroot labs "$@"; }
crash() {  # SIGKILL mysqld 后重新启动同一个容器，保存本次启动的错误日志
  "${COMPOSE[@]}" kill -s KILL mysql >/dev/null 2>&1
  local since; since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  "${COMPOSE[@]}" start mysql >/dev/null 2>&1
  "${COMPOSE[@]}" up -d --wait >/dev/null 2>&1
  "${COMPOSE[@]}" logs --no-log-prefix --since "$since" mysql 2>&1 \
    | sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z [0-9]+ /<timestamp> <thread> /' >"$1"
}
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
log "启动 MySQL 8.4.11（默认持久化参数，开启 binlog）"
"${COMPOSE[@]}" up -d --wait >&2

log "默认参数、redo 文件与 undo 表空间"
{
  echo "SELECT VERSION(), @@log_bin, @@binlog_format;
        SELECT @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@innodb_flush_log_at_timeout, @@innodb_redo_log_capacity,
               @@innodb_undo_tablespaces, @@innodb_undo_log_truncate, @@innodb_max_undo_log_size, @@innodb_doublewrite;
        SELECT FILE_NAME, TABLESPACE_NAME FROM information_schema.FILES WHERE FILE_TYPE = 'UNDO LOG' ORDER BY FILE_NAME;
        SHOW GLOBAL STATUS LIKE 'Innodb_redo_log_%';" | sq -t
} >"$OUT/defaults.txt" 2>&1
"${COMPOSE[@]}" exec -T mysql sh -c 'ls /var/lib/mysql/#innodb_redo | sort -V' >"$OUT/redo-files.txt"

log "场景一：未提交事务 + 已提交事务，kill -9 后重启"
echo "DROP TABLE IF EXISTS account; CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL); INSERT INTO account VALUES (1, 100);" | sq
( echo "BEGIN; UPDATE account SET balance = 0 WHERE id = 1; INSERT INTO account VALUES (2, 999); SELECT 'B 已修改，未提交'; DO SLEEP(60);" | sq -N >"$OUT/crash-session-b.txt" 2>&1 || true ) &
sleep 3
echo "INSERT INTO account VALUES (3, 300); SELECT 'A 已提交';" | sq -N >"$OUT/crash-session-a.txt"
echo "SELECT * FROM account ORDER BY id;" | sq -t >"$OUT/crash-before-kill-visible-to-a.txt"
crash "$OUT/crash-recovery-log.txt"
wait || true
echo "SELECT * FROM account ORDER BY id;" | sq -t >"$OUT/crash-after-restart.txt"

# 场景二：不同 innodb_flush_log_at_trx_commit 下，客户端逐条自动提交并记录确认序号，写入进行中直接 SIGKILL
# 确认日志写在容器内 /tmp（容器可写层），mysqld 被杀后容器停止，重启后文件仍在
lossy() {
  local v="$1" file="$OUT/flush-$1-trial-$2.txt"
  echo "SET GLOBAL innodb_flush_log_at_trx_commit = $v; SET GLOBAL sync_binlog = 0;
        DROP TABLE IF EXISTS ack; CREATE TABLE ack (seq INT PRIMARY KEY); FLUSH BINARY LOGS;" | sq
  local binlog; binlog=$(echo "SHOW BINARY LOG STATUS;" | sq -N | cut -f1)
  "${COMPOSE[@]}" exec -T mysql sh -c 'rm -f /tmp/ack.log; i=1; while [ $i -le 200000 ]; do echo "INSERT INTO ack VALUES ($i); SELECT $i;"; i=$((i+1)); done >/tmp/burst.sql'
  "${COMPOSE[@]}" exec -d -e MYSQL_PWD=example_password mysql sh -c 'mysql -uroot -n -N labs </tmp/burst.sql >/tmp/ack.log 2>&1'
  sleep 4
  docker kill -s KILL "$("${COMPOSE[@]}" ps -q mysql)" >/dev/null
  "${COMPOSE[@]}" start mysql >/dev/null 2>&1; "${COMPOSE[@]}" up -d --wait >/dev/null 2>&1
  local acked rows binlog_rows
  acked=$("${COMPOSE[@]}" exec -T mysql sh -c 'grep -E "^[0-9]+$" /tmp/ack.log | tail -1')
  rows=$(echo "SELECT COUNT(*), COALESCE(MAX(seq), 0) FROM ack;" | sq -N)
  binlog_rows=$(echo "SHOW BINLOG EVENTS IN '$binlog';" | sq -N | grep -c "Write_rows" || true)
  {
    echo "innodb_flush_log_at_trx_commit=$v sync_binlog=0（写入进行中 SIGKILL mysqld；只杀进程，宿主机页缓存仍在）"
    echo "客户端收到确认的最大序号	$acked"
    echo "重启后 InnoDB 行数与最大序号	$rows"
    echo "binlog $binlog 中的 Write_rows 事件数	$binlog_rows"
  } >"$file"
}
log "场景二：innodb_flush_log_at_trx_commit = 1、2、0 下进程崩溃"
rm -f "$OUT"/flush-*.txt
lossy 1 1
lossy 2 1
# =0 的丢失取决于 SIGKILL 落在后台每秒刷盘的哪个时刻，最多重复 3 次，保留每一次的结果
for trial in 1 2 3; do
  lossy 0 "$trial"
  python3 -c "import re,sys; t=open(sys.argv[1]).read(); a=int(re.search(r'最大序号\t(\d+)',t).group(1)); r=int(re.search(r'最大序号\t(\d+)\t',t).group(1)); sys.exit(0 if r < a else 1)" "$OUT/flush-0-trial-$trial.txt" && break
done

write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest，默认配置）"
expect_regex "$OUT/defaults.txt" "\\| +1 +\\| +1 +\\| +1 +\\| +104857600 +\\| +2 +\\| +1 +\\| +1073741824 +\\| ON +\\|" "默认：两个刷盘参数为 1、redo 100MB、2 个 undo 表空间、自动截断、1GB、双写开启"
expect_line "$OUT/redo-files.txt" "_tmp" "#innodb_redo 下有带 _tmp 后缀的备用文件"
[ "$(wc -l <"$OUT/redo-files.txt" | tr -d ' ')" = 32 ] || fail "redo 目录应有 32 个文件"; log "通过：redo 目录共 32 个文件"
expect_line "$OUT/defaults.txt" "undo_001" "默认 undo 表空间 undo_001"
expect_regex "$OUT/crash-after-restart.txt" "\| +1 +\| +100 +\|" "未提交的修改被回滚：id 1 仍是 100"
expect_regex "$OUT/crash-after-restart.txt" "\| +3 +\| +300 +\|" "已提交的 id 3 在"
if grep -q "999" "$OUT/crash-after-restart.txt"; then fail "未提交的 id 2 不应出现"; fi; log "通过：未提交插入的 id 2 不存在"
expect_line "$OUT/crash-recovery-log.txt" "Starting XA crash recovery" "重启日志：XA crash recovery"
python3 - "$OUT" <<'PY'
import re, sys
from pathlib import Path
def read(f):
    t = f.read_text()
    acked = int(re.search(r"确认的最大序号\t(\d+)", t).group(1))
    rows, top = map(int, re.search(r"行数与最大序号\t(\d+)\t(\d+)", t).groups())
    binlog = int(re.search(r"Write_rows 事件数\t(\d+)", t).group(1))
    return acked, rows, binlog
out = Path(sys.argv[1])
for v in (1, 2):
    acked, rows, binlog = read(out / f"flush-{v}-trial-1.txt")
    assert acked > 1000 and rows >= acked, f"={v} 不应丢失已确认的提交：确认 {acked}，重启后 {rows}"
    print(f"通过：={v}：确认 {acked} 个提交，重启后 {rows} 行，进程崩溃不丢已确认提交")
trials = [read(f) for f in sorted(out.glob("flush-0-trial-*.txt"))]
for i, (acked, rows, binlog) in enumerate(trials, 1):
    print(f"信息：=0 第 {i} 次：确认 {acked}，重启后 {rows} 行，binlog 写入事件 {binlog}")
lost = [(a, r, b) for a, r, b in trials if r < a]
assert lost, "=0 在 3 次尝试中都没有丢失已确认提交"
a, r, b = lost[0]
assert b > r, "丢失时 binlog 中的事务应多于 InnoDB 中的行（两者分叉）"
print(f"通过：=0：确认 {a} 个提交，重启后只剩 {r} 行；binlog 有 {b} 个写入事件，比 InnoDB 多 {b - r} 个")
PY
