#!/usr/bin/env bash
# 元数据锁（MDL）：未提交的读事务挡住 ALTER，ALTER 又挡住之后的普通查询；用 performance_schema 找到阻塞者；lock_wait_timeout 与 innodb_lock_wait_timeout（MySQL 8.4.11）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-metadata-locks），结束时删除；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-metadata-locks
trap 'rc=$?; mysql_down "$P" >/dev/null 2>&1 || true; exit $rc' EXIT
mysql_up "$P" >/dev/null 2>&1
mysql_exec "$P" labs <schema/setup.sql >/dev/null
q() { mysql_exec "$P" labs -N -e "$1"; }
f="$OUT/output.tsv"; : >"$f"; W="$OUT/work"; mkdir -p "$W"
elapsed() { echo "SET @t = NOW(3); $1; SELECT CONCAT('elapsed=', ROUND(TIMESTAMPDIFF(MICROSECOND, @t, NOW(3)) / 1e6, 1));"; }

# 场景一：会话 1 读了一行、事务没提交；会话 2 ALTER；会话 3 普通查询。观察后 KILL 会话 1
mysql_exec "$P" labs -N -e "SELECT 'session1'; BEGIN; SELECT amount FROM orders WHERE id = 1; SELECT SLEEP(30); COMMIT;" >"$W/s1.txt" 2>&1 &
sleep 1
mysql_exec "$P" labs -N -e "SET SESSION lock_wait_timeout = 60; $(elapsed "ALTER TABLE orders ADD COLUMN note1 INT, ALGORITHM=INSTANT")" >"$W/s2.txt" 2>&1 &
sleep 1
mysql_exec "$P" labs -N -e "$(elapsed "SELECT amount FROM orders WHERE id = 2")" >"$W/s3.txt" 2>&1 &
sleep 2
q "SELECT t.PROCESSLIST_ID, m.LOCK_TYPE, m.LOCK_STATUS, LEFT(IFNULL(t.PROCESSLIST_INFO, ''), 60)
   FROM performance_schema.metadata_locks m JOIN performance_schema.threads t ON t.THREAD_ID = m.OWNER_THREAD_ID
   WHERE m.OBJECT_SCHEMA = 'labs' AND m.OBJECT_NAME = 'orders' ORDER BY m.LOCK_STATUS, m.LOCK_TYPE" >"$OUT/metadata-locks.tsv"
q "SELECT STATE, LEFT(INFO, 60) FROM performance_schema.processlist WHERE DB = 'labs' AND INFO NOT LIKE '%processlist%' ORDER BY ID" >"$OUT/processlist.tsv"
q "SELECT blocking_pid, waiting_pid, waiting_lock_type, LEFT(waiting_query, 50), sql_kill_blocking_connection FROM sys.schema_table_lock_waits" >"$OUT/lock-waits.tsv"
# 视图把每个持有已授予锁的会话都列为阻塞者，包括正在等待的 ALTER 自己；真正的源头是「阻塞别人、自己不在等」的那个
blocker=$(python3 -c "import sys; r=[l.split('\t') for l in open(sys.argv[1])]; w={x[1] for x in r}; print(*sorted({x[0] for x in r} - w))" "$OUT/lock-waits.tsv")
s1=$(q "SELECT ID FROM performance_schema.processlist WHERE INFO LIKE 'SELECT SLEEP(30)%'")
q "KILL $blocker"
wait
python3 - "$OUT" "$W" "$blocker" "$s1" >>"$f" <<'PY'
import sys, re, os
out, w, blocker, s1 = sys.argv[1:5]
ml = [l.rstrip('\n').split('\t') for l in open(f"{out}/metadata-locks.tsv")]
pl = [l.rstrip('\n').split('\t') for l in open(f"{out}/processlist.tsv")]
def find(rows, needle, col): return next(r for r in rows if needle in r[col])
granted = find(ml, "SLEEP", 3)
print(f"mdl.reader\t会话 1（读完一行、事务未提交）持有 {granted[1]}，状态 {granted[2]}")
alter_locks = sorted({(r[1], r[2]) for r in ml if r[3].startswith("ALTER")})
print(f"mdl.alter\tALTER 已获得或在等待的锁：{'、'.join(f'{t} {s}' for t, s in alter_locks)}")
sel = [r for r in ml if r[3].startswith("SET @t") or r[3].startswith("SELECT amount FROM orders WHERE id = 2")]
print(f"mdl.select\t会话 3（普通查询）：{'、'.join(f'{r[1]} {r[2]}' for r in sel if 'id = 2' in r[3]) or '没有列出'}")
for needle, label in (("ALTER", "ALTER"), ("id = 2", "会话 3")):
    r = find(pl, needle, 1)
    print(f"state.{label}\t{label} 的线程状态：{r[0]}")
lw = [l.rstrip('\n').split('\t') for l in open(f"{out}/lock-waits.tsv")]
cands = sorted({r[0] for r in lw}); waiting = {r[1] for r in lw}
print(f"blocker.rows\tsys.schema_table_lock_waits 列出 {len(lw)} 行，阻塞者候选 {len(cands)} 个，其中包含正在等待的 ALTER 自己={any(c in waiting for c in cands)}")
print(f"blocker\t排除自己也在等待的会话后，剩下的阻塞者就是会话 1={blocker == s1}")
def el(name):
    m = re.search(r"elapsed=([0-9.]+)", open(f"{w}/{name}").read()); return float(m.group(1))
print(f"after-kill.alter\tALTER 一直等到 KILL 会话 1 之后才完成（等待超过 1.5 秒）={el('s2.txt') > 1.5}")
print(f"after-kill.select\t会话 3 也等到 KILL 之后才返回（等待超过 1.5 秒），尽管它要的共享锁与会话 1 并不冲突={el('s3.txt') > 1.5}")
PY
cp "$W"/s*.txt "$OUT/"

# 场景二：只调小 innodb_lock_wait_timeout 不起作用；lock_wait_timeout 才是元数据锁的等待上限
mysql_exec "$P" labs -N -e "BEGIN; SELECT amount FROM orders WHERE id = 1; SELECT SLEEP(12); COMMIT;" >/dev/null 2>&1 &
sleep 1
t0=$(python3 -c 'import time; print(time.time())')
mysql_exec "$P" labs -N -e "SET SESSION innodb_lock_wait_timeout = 1; SET SESSION lock_wait_timeout = 3; ALTER TABLE orders ADD COLUMN note2 INT, ALGORITHM=INSTANT" >"$OUT/timeout.txt" 2>&1 || true
python3 -c "import time; print(f'elapsed={time.time() - $t0:.1f}')" >>"$OUT/timeout.txt"
wait
python3 - "$OUT/timeout.txt" >>"$f" <<'PY'
import sys, re
s = open(sys.argv[1]).read()
code = re.search(r"ERROR (\d+)", s); el = re.search(r"elapsed=([0-9.]+)", s)
print(f"timeout.error\tinnodb_lock_wait_timeout=1、lock_wait_timeout=3：ALTER 报错 {code.group(1) if code else '无'}")
print(f"timeout.seconds\t等待 2.5 到 5 秒之间放弃，即由 lock_wait_timeout=3 决定，而不是 innodb_lock_wait_timeout=1={2.5 <= float(el.group(1)) < 5}")
PY
rm -rf "$W"
mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION())" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
mdl.reader	会话 1（读完一行、事务未提交）持有 SHARED_READ，状态 GRANTED
mdl.alter	ALTER 已获得或在等待的锁：EXCLUSIVE PENDING、SHARED_UPGRADABLE GRANTED
mdl.select	会话 3（普通查询）：SHARED_READ PENDING
state.ALTER	ALTER 的线程状态：Waiting for table metadata lock
state.会话 3	会话 3 的线程状态：Waiting for table metadata lock
blocker.rows	sys.schema_table_lock_waits 列出 4 行，阻塞者候选 2 个，其中包含正在等待的 ALTER 自己=True
blocker	排除自己也在等待的会话后，剩下的阻塞者就是会话 1=True
after-kill.alter	ALTER 一直等到 KILL 会话 1 之后才完成（等待超过 1.5 秒）=True
after-kill.select	会话 3 也等到 KILL 之后才返回（等待超过 1.5 秒），尽管它要的共享锁与会话 1 并不冲突=True
timeout.error	innodb_lock_wait_timeout=1、lock_wait_timeout=3：ALTER 报错 1205
timeout.seconds	等待 2.5 到 5 秒之间放弃，即由 lock_wait_timeout=3 决定，而不是 innodb_lock_wait_timeout=1=True
EXPECTED
log "全部通过，输出在 $OUT"
