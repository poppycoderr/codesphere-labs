#!/usr/bin/env bash
# 执行生成的 SQL 之前的几道检查各能挡住什么：最小权限账号、只读事务、单语句、超时、输出上限、成本估算、黄金数据，以及 JDBC 一侧的默认行为
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-sql-guard）与固定 digest 的 temurin 25 容器，结束时删除；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-sql-guard
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
CONNECTOR="com.mysql:mysql-connector-j:9.7.0"
CONNECTOR_SHA256=0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44
trap 'mysql_down "$P" >/dev/null 2>&1 || true' EXIT
cid=$(mysql_up "$P" 2>/dev/null)
mysql_exec "$P" labs <schema/setup.sql >/dev/null
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
now() { python3 -c 'import time; print(f"{time.time():.2f}")'; }
# run <账号> <SQL>：以指定账号执行，返回结果的最后一行；出错时返回「ERROR 编号：消息」
run() {
  local u=$1 r
  r=$(printf '%s\n' "$2" | docker compose -p "$P" -f ../../shared/docker/mysql84/compose.yaml exec -T -e MYSQL_PWD=example_password mysql mysql "-u$u" labs -N 2>&1 | tail -1 || true)
  printf '%s' "$r" | sed -E "s/^ERROR ([0-9]+) \([^)]*\) at line [0-9]+: /ERROR \1：/; s/'report_ro'@'[^']*'/report_ro/"
}

# 1. 最小权限的查询账号：能挡住什么
out priv.delete "DELETE FROM orders → $(run report_ro 'DELETE FROM orders')"
out priv.sensitive_column "SELECT phone FROM users → $(run report_ro 'SELECT phone FROM users LIMIT 1')"
out priv.system_table "SELECT user FROM mysql.user → $(run report_ro 'SELECT user FROM mysql.user')"
out priv.outfile "SELECT … INTO OUTFILE → $(run report_ro "SELECT 1 INTO OUTFILE '/tmp/x'")"
out priv.locking_read "SELECT … FOR UPDATE → $(run report_ro 'SELECT * FROM orders FOR UPDATE')"
out priv.view_definer "DEFINER 视图（不含 phone 列）SELECT COUNT(*) → $(run report_ro 'SELECT COUNT(*) FROM v_users')"
out priv.view_invoker "INVOKER 视图 SELECT COUNT(*) → $(run report_ro 'SELECT COUNT(*) FROM v_users_invoker' | cut -c1-60)…"
out priv.view_update "UPDATE 视图 → $(run report_ro "UPDATE v_users SET city = 'Z'")"
# 权限挡不住资源消耗
t=$(now); r=$(run report_ro 'SELECT SLEEP(2)')
out priv.sleep "SELECT SLEEP(2) → 返回 $r，用时 $(python3 -c "print('不少于 2 秒' if $(now) - $t >= 2 else '少于 2 秒')")"

# 2. 只读事务：账号有写权限也写不进去
out readonly.trx "读写账号在 START TRANSACTION READ ONLY 里 DELETE → $(run app_rw 'START TRANSACTION READ ONLY; DELETE FROM orders WHERE id = 5')；orders 仍有 $(run app_rw 'SELECT COUNT(*) FROM orders') 行"

# 3. 单语句：PREPARE 只接受一条语句
out single.prepare "PREPARE 两条语句 → $(run app_rw "PREPARE s FROM 'SELECT 1; DELETE FROM orders'" | cut -c1-70)…；orders 仍有 $(run app_rw 'SELECT COUNT(*) FROM orders') 行"

# 4. 超时：会话变量会被语句里的提示覆盖
t=$(now); r=$(run report_ro 'SET SESSION MAX_EXECUTION_TIME = 500; SELECT SLEEP(2)')
out timeout.session "会话 MAX_EXECUTION_TIME=500，SELECT SLEEP(2) → 返回 $r（1 表示被打断），$(python3 -c "print('1 秒内返回' if $(now) - $t < 1.5 else '超过 1.5 秒')")"
out timeout.session_join "会话 MAX_EXECUTION_TIME=500，10 万行自连接 → $(run report_ro 'SET SESSION MAX_EXECUTION_TIME = 500; SELECT COUNT(*) FROM events a JOIN events b ON a.kind = b.kind')"
t=$(now); r=$(run report_ro 'SET SESSION MAX_EXECUTION_TIME = 500; SELECT /*+ MAX_EXECUTION_TIME(20000) */ SLEEP(2)')
out timeout.hint_override "同一会话，语句带 /*+ MAX_EXECUTION_TIME(20000) */ → 返回 $r（0 表示睡满），$(python3 -c "print('用时不少于 2 秒' if $(now) - $t >= 2 else '少于 2 秒')")"

# 5. 输出上限
out limit.select_limit "会话 sql_select_limit=3，SELECT id FROM events → $(printf 'SET SESSION sql_select_limit = 3; SELECT id FROM events;\n' | docker compose -p "$P" -f ../../shared/docker/mysql84/compose.yaml exec -T -e MYSQL_PWD=example_password mysql mysql -ureport_ro labs -N | wc -l | tr -d ' ') 行；语句自带 LIMIT 10 → $(printf 'SET SESSION sql_select_limit = 3; SELECT id FROM events LIMIT 10;\n' | docker compose -p "$P" -f ../../shared/docker/mysql84/compose.yaml exec -T -e MYSQL_PWD=example_password mysql mysql -ureport_ro labs -N | wc -l | tr -d ' ') 行"
out limit.wrap "外层包一层 SELECT * FROM (…) t LIMIT 4，内层 LIMIT 10 → $(printf 'SELECT * FROM (SELECT id FROM events LIMIT 10) t LIMIT 4;\n' | docker compose -p "$P" -f ../../shared/docker/mysql84/compose.yaml exec -T -e MYSQL_PWD=example_password mysql mysql -ureport_ro labs -N | wc -l | tr -d ' ') 行"

# 6. 成本估算：执行前用 EXPLAIN FORMAT=JSON 看 query_cost
cost() { printf 'EXPLAIN FORMAT=JSON %s;\n' "$1" | docker compose -p "$P" -f ../../shared/docker/mysql84/compose.yaml exec -T -e MYSQL_PWD=example_password mysql mysql -ureport_ro labs -N -r | python3 -c 'import json,sys; print(json.load(sys.stdin)["query_block"]["cost_info"]["query_cost"])'; }
out cost.small "按状态汇总 5 行订单：query_cost=$(cost 'SELECT status, SUM(amount) FROM orders GROUP BY status')"
out cost.cartesian "10 万行事件表按低基数列自连接：query_cost=$(cost 'SELECT COUNT(*) FROM events a JOIN events b ON a.kind = b.kind')"
out cost.explain_dml "EXPLAIN DELETE（查询账号）→ $(run report_ro 'EXPLAIN DELETE FROM orders')"

# 7. 黄金数据：语法、权限、成本都没问题，结果是错的
out golden.wrong "订单连明细后求订单金额合计 → $(run report_ro 'SELECT SUM(o.amount) FROM orders o JOIN order_items i ON i.order_id = o.id')（手算答案 480.00）"
out golden.right "直接在订单表求和 → $(run report_ro 'SELECT SUM(amount) FROM orders')"

# 8. JDBC 一侧（Connector/J 9.7.0，默认连接参数）
jar=$(maven_jar "$CONNECTOR")
got=$(shasum -a 256 "$jar" 2>/dev/null | cut -d' ' -f1 || sha256sum "$jar" | cut -d' ' -f1)
[ "$got" = "$CONNECTOR_SHA256" ] || fail "Connector/J jar 校验失败：$got"
docker run --rm --network "container:$cid" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -cp "/cache/m2/$(basename "$jar")" src/JdbcGuard.java >>"$f"

printf 'mysql_version: %s\n' "$(run app_rw 'SELECT VERSION()')" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)" "jdk25_image: $J25" "connector: $CONNECTOR sha256=$CONNECTOR_SHA256"
cat "$f" >&2
expect_line "$OUT/mysql-version.txt" "mysql_version: 8.4.11" "运行在 MySQL 8.4.11"
expect_regex "$f" "^priv\.delete	.*ERROR 1142：DELETE command denied to user report_ro for table 'orders'$" "查询账号不能写"
expect_regex "$f" "^priv\.sensitive_column	.*ERROR 1142：SELECT command denied to user report_ro for table 'users'$" "查询账号读不到没有授权的表"
expect_regex "$f" "^priv\.system_table	.*ERROR 1142：" "查询账号读不到系统表"
expect_regex "$f" "^priv\.outfile	.*ERROR 1227：.*FILE privilege" "没有 FILE 权限不能写文件"
expect_regex "$f" "^priv\.locking_read	.*ERROR 1142：SELECT with locking clause command denied" "只有 SELECT 权限不能加锁读"
expect_line "$f" "priv.view_definer	DEFINER 视图（不含 phone 列）SELECT COUNT(*) → 5" "DEFINER 视图可以作为列级的受限入口"
expect_regex "$f" "^priv\.view_invoker	.*ERROR 1356：" "INVOKER 视图要求调用者自己有底表权限"
expect_regex "$f" "^priv\.view_update	.*ERROR 1142：UPDATE command denied" "只授予 SELECT 的视图不能更新"
expect_line "$f" "priv.sleep	SELECT SLEEP(2) → 返回 0，用时 不少于 2 秒" "权限不限制资源消耗"
expect_line "$f" "readonly.trx	读写账号在 START TRANSACTION READ ONLY 里 DELETE → ERROR 1792：Cannot execute statement in a READ ONLY transaction.；orders 仍有 5 行" "只读事务挡住写入"
expect_regex "$f" "^single\.prepare	PREPARE 两条语句 → ERROR 1064：.*；orders 仍有 5 行$" "PREPARE 拒绝多语句"
expect_line "$f" "timeout.session	会话 MAX_EXECUTION_TIME=500，SELECT SLEEP(2) → 返回 1（1 表示被打断），1 秒内返回" "会话超时打断了 SLEEP"
expect_regex "$f" "^timeout\.session_join	.*ERROR 3024：Query execution was interrupted, maximum statement execution time exceeded$" "会话超时终止大查询"
expect_line "$f" "timeout.hint_override	同一会话，语句带 /*+ MAX_EXECUTION_TIME(20000) */ → 返回 0（0 表示睡满），用时不少于 2 秒" "语句里的提示覆盖了会话超时"
expect_line "$f" "limit.select_limit	会话 sql_select_limit=3，SELECT id FROM events → 3 行；语句自带 LIMIT 10 → 10 行" "sql_select_limit 只对没写 LIMIT 的语句生效"
expect_line "$f" "limit.wrap	外层包一层 SELECT * FROM (…) t LIMIT 4，内层 LIMIT 10 → 4 行" "外层包装的 LIMIT 不能被内层覆盖"
expect_regex "$f" "^cost\.explain_dml	.*ERROR 1142：" "查询账号连 EXPLAIN DELETE 也不能执行"
expect_line "$f" "golden.wrong	订单连明细后求订单金额合计 → 880.00（手算答案 480.00）" "能通过前面所有检查的查询，结果仍然可能是错的"
expect_line "$f" "golden.right	直接在订单表求和 → 480.00" "正确查询与手算答案一致"
expect_line "$f" "jdbc.driver	MySQL Connector/J mysql-connector-j-9.7.0" "Connector/J 9.7.0"
expect_regex "$f" "^jdbc\.multi_statement	默认连接参数下执行两条语句：SQLSyntaxErrorException" "JDBC 默认不允许多语句"
expect_regex "$f" "^jdbc\.read_only	.*SQLException（Connection is read-only\. Queries leading to data modification are not allowed\.）$" "只读连接拒绝写入"
expect_regex "$f" "^jdbc\.query_timeout	.*3 秒内抛出 MySQLTimeoutException" "setQueryTimeout 不受语句提示影响"
expect_line "$f" "jdbc.max_rows	setMaxRows(100) 后查询 10 万行的表：返回 100 行" "setMaxRows 限制返回行数"
expect_line "$f" "jdbc.orders_after	实验结束后 orders 仍有 5 行" "所有写入尝试都没有生效"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
small = float(re.search(r"^cost\.small\t.*query_cost=([\d.]+)", t, re.M)[1])
big = float(re.search(r"^cost\.cartesian\t.*query_cost=([\d.]+)", t, re.M)[1])
assert small < 10 and big > 1e8, (small, big)
print(f"通过：成本估算相差 {big / small:.0f} 倍（{small} 与 {big:.0f}）")
PY
log "全部通过，输出在 $OUT"
