#!/usr/bin/env bash
# 误删恢复演练：全量逻辑备份 → 正常写入 → 误删（与合法写入并发）→ 在隔离实例恢复 → 用复制 SQL 线程重放归档 binlog，
# 停在误删事务之前、跳过它、补回之后的合法写入 → 与对照实例比对业务校验和；另含备份损坏与 binlog 缺口两个失败路径
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：3 个容器各 2 CPU、1 GB 内存；约 3 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
WORK=build/work; rm -rf "$WORK"; mkdir -p "$WORK/archive"
COMPOSE=(docker compose -f compose.yaml)
on() { local n="$1"; shift; "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password "$n" mysql -uroot "$@"; }
cid() { "${COMPOSE[@]}" ps -q "$1"; }
utc() { python3 -c "import datetime; print(datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds'))"; }
ev() { printf '%s\t%s\t%s\n' "$1" "$(utc)" "${2:-}" >>"$OUT/timeline.tsv"; }
checks() { on "$1" -N <schema/02-checks.sql; }
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
log "启动 source、control 与隔离的 restore 实例"
"${COMPOSE[@]}" up -d --wait >&2
for n in source control; do on "$n" <schema/01-business.sql; done

log "t0：两边各下 1,000 单，source 做全量逻辑备份"
for n in source control; do echo "CALL shop.place_orders(1, 1000, 0);" | on "$n"; done
checks control >"$OUT/control-at-backup-checks.tsv"
echo "FLUSH BINARY LOGS;" | on source
set +e
"${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password source sh -c \
  'mysqldump -uroot --single-transaction --source-data=2 --set-gtid-purged=ON --routines --triggers --events --databases shop >/tmp/full.sql 2>/tmp/full.err'
DUMP_EXIT=$?
set -e
docker cp "$(cid source):/tmp/full.sql" "$WORK/full.sql" >/dev/null
docker cp "$(cid source):/tmp/full.err" "$OUT/backup-stderr.txt" >/dev/null
{
  echo "command	mysqldump --single-transaction --source-data=2 --set-gtid-purged=ON --routines --triggers --events --databases shop"
  echo "exit_code	$DUMP_EXIT"
  echo "bytes	$(wc -c <"$WORK/full.sql" | tr -d ' ')"
  echo "sha256	$(sha "$WORK/full.sql")"
  echo "gtid_purged	$(grep -o "GTID_PURGED=.*" "$WORK/full.sql" | head -1)"
  echo "binlog_position	$(grep -m1 "CHANGE REPLICATION SOURCE TO" "$WORK/full.sql")"
} >"$OUT/backup-manifest.tsv"
ev full_backup_finished "exit=$DUMP_EXIT"

log "t1：正常写入 1,001—1,200"
for n in source control; do echo "CALL shop.place_orders(1001, 1200, 0);" | on "$n"; done
echo "FLUSH BINARY LOGS;" | on source
ev normal_writes_committed "orders 1001..1200"

log "t2—t3：合法写入 1,201—1,250（每单间隔 20ms）进行中，另一个会话误删 order_items"
( echo "CALL shop.place_orders(1201, 1250, 0.02);" | on source ) &
BG=$!
sleep 0.5
echo "DELETE FROM shop.order_items WHERE order_id > 0; SELECT 'bad_delete_committed_at', NOW(6), ROW_COUNT();" | on source -N >"$OUT/bad-delete.tsv"
wait "$BG"
ev accidental_delete_committed "$(cut -f2- "$OUT/bad-delete.tsv")"
echo "CALL shop.place_orders(1201, 1250, 0);" | on control
echo "FLUSH BINARY LOGS;" | on source
ev incident_detected
checks source >"$OUT/source-after-incident-checks.tsv"
checks control >"$OUT/control-final-checks.tsv"
TARGET=$(echo "SELECT @@global.gtid_executed;" | on source -N | tr -d '\n')
echo "$TARGET" >"$OUT/source-gtid-executed.txt"

log "归档 binlog 并定位误删事务"
echo "SHOW BINARY LOGS;" | on source -N >"$OUT/binlog-list.tsv"
: >"$OUT/binlog-archive-manifest.tsv"
while IFS=$'\t' read -r f size _; do
  docker cp "$(cid source):/var/lib/mysql/$f" "$WORK/archive/$f" >/dev/null
  printf '%s\t%s\t%s\n' "$f" "$size" "$(sha "$WORK/archive/$f")" >>"$OUT/binlog-archive-manifest.tsv"
  echo "SHOW BINLOG EVENTS IN '$f';" | on source -N | sed "s/^/$f\t/" >>"$WORK/binlog-events.tsv"
done <"$OUT/binlog-list.tsv"
python3 - "$WORK/binlog-events.tsv" "$OUT/bad-transaction.tsv" "$OUT/orders-per-binlog.tsv" <<'PY'
import re, sys
gtid = table = None; bad = []; per_file = {}
for line in open(sys.argv[1]):
    f = line.rstrip("\n").split("\t")
    file, etype, info = f[0], f[3], f[6]
    if etype == "Gtid":
        gtid = re.search(r"'([^']+)'", info).group(1); table = None
    elif etype == "Table_map":
        table = info
    elif etype == "Delete_rows" and table and "(shop.order_items)" in table:
        if not bad or bad[-1][0] != gtid: bad.append((gtid, file, f[2]))
    elif etype == "Write_rows" and table and "(shop.orders)" in table:
        per_file[file] = per_file.get(file, 0) + 1
assert len(bad) == 1, bad
with open(sys.argv[2], "w") as o:
    o.write("gtid\tbinlog_file\tposition\n" + "\t".join(bad[0]) + "\n")
with open(sys.argv[3], "w") as o:
    o.write("binlog_file\torder_insert_events\n" + "".join(f"{k}\t{v}\n" for k, v in sorted(per_file.items())))
PY
BAD=$(tail -1 "$OUT/bad-transaction.tsv" | cut -f1)
ev bad_transaction_located "$BAD"
echo "SELECT sequence, committed_at FROM shop.operation_markers WHERE sequence > 1200 ORDER BY sequence;" | on source -N >"$OUT/markers-around-incident.tsv"

# restore 实例：清空 GTID 与业务库，按参数恢复全量备份
fresh_restore() {
  echo "STOP REPLICA; RESET REPLICA ALL; DROP DATABASE IF EXISTS shop; RESET BINARY LOGS AND GTIDS;" | on restore 2>/dev/null || \
    echo "DROP DATABASE IF EXISTS shop; RESET BINARY LOGS AND GTIDS;" | on restore
  on restore <"$1"
}
# relay_from_archive <binlog 文件...>：把归档 binlog 依次作为 relay log 放进 restore 的数据目录，重启后指向第一个文件
relay_from_archive() {
  local i=1 f idx=""
  for f in "$@"; do
    docker cp "$WORK/archive/$f" "$(cid restore):/var/lib/mysql/restore-relay-bin.$(printf '%06d' $i)" >/dev/null
    idx="$idx./restore-relay-bin.$(printf '%06d' $i)"$'\n'; i=$((i + 1))
  done
  printf '%s' "$idx" >"$WORK/restore-relay-bin.index"
  docker cp "$WORK/restore-relay-bin.index" "$(cid restore):/var/lib/mysql/restore-relay-bin.index" >/dev/null
  "${COMPOSE[@]}" exec -T -u root restore sh -c 'chown mysql:mysql /var/lib/mysql/restore-relay-bin.*'
  "${COMPOSE[@]}" restart restore >/dev/null 2>&1; "${COMPOSE[@]}" up -d --wait restore >/dev/null 2>&1
  echo "CHANGE REPLICATION SOURCE TO SOURCE_HOST = 'binlog-archive', RELAY_LOG_FILE = 'restore-relay-bin.000001', RELAY_LOG_POS = 4;" | on restore
}
wait_sql_stopped() { local i; for i in $(seq 120); do
  [ "$(echo "SELECT SERVICE_STATE FROM performance_schema.replication_applier_status;" | on restore -N)" = OFF ] && return; sleep 0.5; done; fail "SQL 线程没有停止"; }
ARCHIVE=($(cut -f1 "$OUT/binlog-list.tsv"))

log "失败路径一：备份文件损坏"
cp "$WORK/full.sql" "$WORK/full-corrupted.sql"
python3 - "$WORK/full-corrupted.sql" <<'PY'
import sys
p = sys.argv[1]; b = bytearray(open(p, "rb").read())
# 把第一行库存 (1,<available>,<version>) 的 available 首位数字改掉：导入仍会成功
i = b.index(b"INSERT INTO `inventory` VALUES (1,") + len(b"INSERT INTO `inventory` VALUES (1,")
b[i] = ord("7") if b[i] != ord("7") else ord("3")
open(p, "wb").write(b)
PY
{
  echo "expected_sha256	$(grep sha256 "$OUT/backup-manifest.tsv" | cut -f2)"
  echo "actual_sha256	$(sha "$WORK/full-corrupted.sql")"
  if [ "$(sha "$WORK/full-corrupted.sql")" != "$(grep sha256 "$OUT/backup-manifest.tsv" | cut -f2)" ]; then echo "gate	拒绝恢复：校验和不一致"; fi
  echo "# 如果跳过校验直接导入："
  set +e; fresh_restore "$WORK/full-corrupted.sql" 2>&1; echo "import_exit_code	$?"; set -e
} >"$OUT/failure-corrupted-backup.tsv"
checks restore >"$OUT/failure-corrupted-backup-checks.tsv"

log "恢复：校验备份 → 在隔离实例导入 → 重放到误删之前 → 跳过误删 → 补回之后的合法写入"
[ "$(sha "$WORK/full.sql")" = "$(grep sha256 "$OUT/backup-manifest.tsv" | cut -f2)" ] || fail "备份校验和不一致"
ev restore_started
fresh_restore "$WORK/full.sql"
checks restore >"$OUT/restore-baseline-checks.tsv"
ev restore_readable "全量备份导入完成，基线校验和已记录"
relay_from_archive "${ARCHIVE[@]}"
echo "START REPLICA SQL_THREAD UNTIL SQL_BEFORE_GTIDS = '$BAD';" | on restore
wait_sql_stopped
checks restore >"$OUT/restore-before-bad-checks.tsv"
echo "SELECT @@global.gtid_executed;" | on restore -N >"$OUT/restore-before-bad-gtid.txt"
ev replay_stopped_before_bad "$BAD"
echo "SET GTID_NEXT = '$BAD'; BEGIN; COMMIT; SET GTID_NEXT = 'AUTOMATIC';" | on restore
ev bad_transaction_skipped "以空事务占用 $BAD"
echo "START REPLICA SQL_THREAD; SELECT WAIT_FOR_EXECUTED_GTID_SET('$TARGET', 120); STOP REPLICA;" | on restore -N >/dev/null
checks restore >"$OUT/restore-final-checks.tsv"
echo "SELECT @@global.gtid_executed;" | on restore -N >"$OUT/restore-final-gtid.txt"
ev restore_verified "业务校验和与对照实例比较"
echo "SELECT id, total_amount FROM shop.orders WHERE id IN (1, 1200, 1250) ORDER BY id;" | on restore -N >"$OUT/restore-smoke.tsv"
ev restore_switchable "只读冒烟查询完成"

log "失败路径二：归档中缺少一个 binlog 文件"
# 删掉备份之后的第一个 binlog（全量备份记录的起点文件），其中是订单 1,001—1,200
MISSING=$(grep binlog_position "$OUT/backup-manifest.tsv" | grep -o "SOURCE_LOG_FILE='[^']*'" | cut -d"'" -f2)
GAPPED=(); for f in "${ARCHIVE[@]}"; do [ "$f" = "$MISSING" ] || GAPPED+=("$f"); done
fresh_restore "$WORK/full.sql"
relay_from_archive "${GAPPED[@]}"
echo "START REPLICA SQL_THREAD UNTIL SQL_BEFORE_GTIDS = '$BAD';" | on restore
wait_sql_stopped
echo "SET GTID_NEXT = '$BAD'; BEGIN; COMMIT; SET GTID_NEXT = 'AUTOMATIC';" | on restore
echo "START REPLICA SQL_THREAD;" | on restore
sleep 5
{
  echo "missing_binlog_file	$MISSING"
  echo "applier_errors	$(echo "SELECT COUNT(*) FROM performance_schema.replication_applier_status_by_worker WHERE LAST_ERROR_NUMBER <> 0;" | on restore -N)"
  echo "restore_gtid_executed	$(echo "SELECT @@global.gtid_executed;" | on restore -N | tr -d '\n')"
  echo "target_is_subset	$(echo "SELECT GTID_SUBSET('$TARGET', @@global.gtid_executed);" | on restore -N)"
  echo "missing_gtids	$(echo "SELECT GTID_SUBTRACT('$TARGET', @@global.gtid_executed);" | on restore -N | tr -d '\n')"
} >"$OUT/failure-binlog-gap.tsv"
echo "STOP REPLICA;" | on restore
checks restore >"$OUT/failure-binlog-gap-checks.tsv"

{ "${COMPOSE[@]}" config --images | sed 's/^/image: /'; echo "cpus: 2（每个容器）"; echo "mem_limit: 1g（每个容器）"; echo "config: config/common.cnf"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest；mysqldump 8.4.11）"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
