#!/usr/bin/env bash
# 完整流程：启动容器 → 造数 → 读取每张表主键根页的 PAGE_LEVEL / PAGE_N_RECS 与叶子页数 → 主键点查 → 大表 DDL → 断言
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：2 CPU、3 GB 内存、约 5 GB 磁盘；造数约 5—8 分钟
source "$(dirname "$0")/env.sh"
OUT="${1:-build/run}"; mkdir -p "$OUT"
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
log "启动 MySQL 8.4.11 容器（compose 项目 csl-table-size）"
"${COMPOSE[@]}" up -d --wait >&2
log "造数：10 万、500 万、2,000 万、100 万宽表（约 5—8 分钟）"
sql <schema/01-schema.sql
t0=$(date +%s); sql <schema/02-seed.sql >/dev/null; log "造数用时 $(( $(date +%s) - t0 )) 秒"

log "读取 B+ 树根页"
# 页大小 16KB；页头从偏移 38 开始，PAGE_N_RECS 在其后 16 字节，PAGE_LEVEL 在其后 26 字节，均为 2 字节大端
u16() { "${COMPOSE[@]}" exec -T mysql sh -c "od -An -tu1 -j $2 -N 2 /var/lib/mysql/labs/$1.ibd" | awk '{print $1 * 256 + $2}'; }
{
  printf 'table\trows\tavg_row_bytes\troot_page\ttree_height\troot_records\tleaf_pages\trows_per_leaf\tibd_bytes\n'
  for t in orders orders_big narrow_20m wide_1m; do
    echo "FLUSH TABLES $t FOR EXPORT; UNLOCK TABLES;" | sql
    root=$(echo "SELECT i.PAGE_NO FROM information_schema.INNODB_INDEXES i JOIN information_schema.INNODB_TABLES t USING (TABLE_ID)
                 WHERE t.NAME = 'labs/$t' AND i.NAME = 'PRIMARY';" | sql -N)
    level=$(u16 "$t" $(( root * 16384 + 64 )))
    nrecs=$(u16 "$t" $(( root * 16384 + 54 )))
    leaf=$(echo "SELECT stat_value FROM mysql.innodb_index_stats WHERE database_name = 'labs' AND table_name = '$t'
                 AND index_name = 'PRIMARY' AND stat_name = 'n_leaf_pages';" | sql -N)
    rows=$(echo "SELECT COUNT(*) FROM $t;" | sql -N)
    avg=$(echo "SELECT AVG_ROW_LENGTH FROM information_schema.tables WHERE table_schema = 'labs' AND table_name = '$t';" | sql -N)
    size=$("${COMPOSE[@]}" exec -T mysql stat -c %s "/var/lib/mysql/labs/$t.ibd")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$t" "$rows" "$avg" "$root" $(( level + 1 )) "$nrecs" "$leaf" $(( rows / leaf )) "$size"
  done
} >"$OUT/btree.tsv"

log "主键点查：每张表 20,000 次随机主键查询，冷、热两轮的平均耗时与物理读（服务端计时）"
# 先重启，让所有表从冷缓冲池开始
"${COMPOSE[@]}" restart mysql >/dev/null 2>&1; "${COMPOSE[@]}" up -d --wait >/dev/null 2>&1
sql <<'SQL'
DROP PROCEDURE IF EXISTS point_lookups;
DELIMITER //
CREATE PROCEDURE point_lookups(IN tbl VARCHAR(64), IN max_id BIGINT, IN n INT)
BEGIN
  DECLARE i INT DEFAULT 0; DECLARE pass INT DEFAULT 1; DECLARE t0 DATETIME(6); DECLARE r0 BIGINT;
  SET @sql = CONCAT('SELECT id INTO @x FROM ', tbl, ' WHERE id = ?');
  PREPARE s FROM @sql;
  -- 同一批主键查两轮：第一轮可能读盘（cold），第二轮页都已在缓冲池（hot）
  WHILE pass <= 2 DO
    SELECT variable_value INTO r0 FROM performance_schema.global_status WHERE variable_name = 'Innodb_buffer_pool_reads';
    SET i = 0; SET t0 = NOW(6);
    WHILE i < n DO SET @id = 1 + ((i * 2654435761) % max_id); EXECUTE s USING @id; SET i = i + 1; END WHILE;
    SELECT tbl, IF(pass = 1, 'cold', 'hot'), n, ROUND(TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) / n, 2),
           (SELECT variable_value FROM performance_schema.global_status WHERE variable_name = 'Innodb_buffer_pool_reads') - r0;
    SET pass = pass + 1;
  END WHILE;
  DEALLOCATE PREPARE s;
END //
DELIMITER ;
SQL
{ echo "CALL point_lookups('orders', 100000, 20000);"; echo "CALL point_lookups('orders_big', 5000000, 20000);"
  echo "CALL point_lookups('narrow_20m', 20000000, 20000);"; echo "CALL point_lookups('wide_1m', 1000000, 20000);"; } \
  | sql -N | { printf 'table\tpass\tlookups\tavg_micros\tphysical_reads\n'; cat; } >"$OUT/point-lookups.tsv"

log "2,000 万行窄表上的 DDL"
timed() { printf 'SET @t = NOW(6);\n%s;\nSELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6));\n' "$1" | sql -N | tail -1; }
{
  printf 'operation\tmicros\n'
  printf '%s\t%s\n' "ADD COLUMN note VARCHAR(20) NULL, ALGORITHM=INSTANT" "$(timed "ALTER TABLE narrow_20m ADD COLUMN note VARCHAR(20) NULL, ALGORITHM=INSTANT")"
  printf '%s\t%s\n' "ADD INDEX idx_k (k), ALGORITHM=INPLACE, LOCK=NONE" "$(timed "ALTER TABLE narrow_20m ADD INDEX idx_k (k), ALGORITHM=INPLACE, LOCK=NONE")"
  printf '%s\t%s\n' "MODIFY v BIGINT NOT NULL（ALGORITHM=COPY）" "$(timed "ALTER TABLE narrow_20m MODIFY v BIGINT NOT NULL, ALGORITHM=COPY")"
  printf '%s\t%s\n' "SELECT COUNT(*)" "$(timed "SELECT COUNT(*) INTO @c FROM narrow_20m")"
} >"$OUT/ddl.tsv"
echo "ALTER TABLE narrow_20m MODIFY v INT NOT NULL, ALGORITHM=INPLACE;" | sql >"$OUT/ddl-inplace-rejected.txt" 2>&1 || true

{ echo "image: $("${COMPOSE[@]}" config --images)"; echo "cpus: 2"; echo "mem_limit: 3g"; echo "config: config/mysql.cnf"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（compose.yaml 固定 digest）"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
