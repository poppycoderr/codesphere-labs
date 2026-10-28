#!/usr/bin/env bash
# 冲突时到底做了什么：INSERT IGNORE、ON DUPLICATE KEY UPDATE 的影响行数与多唯一键冲突、自增值消耗、VALUES() 弃用、REPLACE 的删除语义（MySQL 8.4.11）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-upsert-semantics），结束时删除；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-upsert-semantics
trap 'mysql_down "$P" >/dev/null 2>&1 || true' EXIT
mysql_up "$P" >/dev/null 2>&1
# --force：严格模式下超长值那一条预期报错，脚本继续执行
mysql_exec "$P" labs -N --force <schema/upsert.sql >"$OUT/raw.tsv" 2>"$OUT/errors.txt" || true
f="$OUT/output.tsv"
python3 - "$OUT/raw.tsv" "$OUT/errors.txt" >"$f" <<'PY'
import sys
v = dict(l.rstrip('\n').split('\t', 1) for l in open(sys.argv[1]))
err = open(sys.argv[2]).read()
rc, code = v['ignore.dup'].split('|'); print(f"ignore.dup\tINSERT IGNORE 遇到重复：影响 {rc} 行，警告 {code}")
rc, code, val = v['ignore.long'].split('|'); print(f"ignore.long\tINSERT IGNORE 写入 20 个字符到 VARCHAR(10)：影响 {rc} 行，警告 {code}，存进去的是 {val}")
rc, code, val = v['ignore.null'].split('|'); print(f"ignore.null\tINSERT IGNORE 给 NOT NULL 列写 NULL：影响 {rc} 行，警告 {code}，存进去的是 {val}")
print(f"strict.long\t不带 IGNORE 的同一条插入：报错 1406={'ERROR 1406' in err}")
print(f"odku.rows\tON DUPLICATE KEY UPDATE 影响行数：新插入 {v['odku.insert']}，更新且值变了 {v['odku.changed']}，更新但值没变 {v['odku.same']}")
print(f"autoinc\t5 次走了更新分支的 upsert 之后，新插入一行得到 id {v['autoinc.next-id']}，而表中最大 id 是 {v['autoinc.max-before']}")
print(f"multi\temail 与 alice 冲突、phone 与 bob 冲突：影响 {v['multi.rows']} 行，结果 {v['multi.result']}（只改了一行）")
print(f"values\tON DUPLICATE KEY UPDATE 里用 VALUES(col)：警告 {v['values.warning']}（已弃用）")
items, audit = v['replace.before'].split('|'); print(f"replace.before\tREPLACE 之前：order_items {items} 行，删除审计 {audit} 条")
print(f"replace.one\tREPLACE 与 bob 冲突：影响 {v['replace.one.rows']} 行，bob 的 id 从 2 变成 {v['replace.one.id']}，之后 order_items 与审计：{v['replace.one.after']}")
items, audit = v['replace.two.after'].split('|')
print(f"replace.two\tREPLACE 同时与 alice（email）和 grace（phone）冲突：影响 {v['replace.two.rows']} 行，剩下 {v['replace.two.left']}，order_items 剩 {items} 行，删除审计共 {audit} 条")
PY
mysql_exec "$P" -N -e "SELECT CONCAT('mysql_version: ', VERSION())" >"$OUT/mysql-version.txt"
write_environment "$OUT/environment.txt" "mysql_image: $(sed -n 's/^ *image: //p' ../../shared/docker/mysql84/compose.yaml)"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
ignore.dup	INSERT IGNORE 遇到重复：影响 0 行，警告 1062
ignore.long	INSERT IGNORE 写入 20 个字符到 VARCHAR(10)：影响 1 行，警告 1265，存进去的是 a-very-lon
ignore.null	INSERT IGNORE 给 NOT NULL 列写 NULL：影响 1 行，警告 1048，存进去的是 []
strict.long	不带 IGNORE 的同一条插入：报错 1406=True
odku.rows	ON DUPLICATE KEY UPDATE 影响行数：新插入 1，更新且值变了 2，更新但值没变 0
autoinc	5 次走了更新分支的 upsert 之后，新插入一行得到 id 12，而表中最大 id 是 6
multi	email 与 alice 冲突、phone 与 bob 冲突：影响 2 行，结果 1:mix,2:bob（只改了一行）
values	ON DUPLICATE KEY UPDATE 里用 VALUES(col)：警告 1287（已弃用）
replace.before	REPLACE 之前：order_items 3 行，删除审计 0 条
replace.one	REPLACE 与 bob 冲突：影响 2 行，bob 的 id 从 2 变成 15，之后 order_items 与审计：2|delete:2
replace.two	REPLACE 同时与 alice（email）和 grace（phone）冲突：影响 3 行，剩下 a@example.com/700/merged，order_items 剩 0 行，删除审计共 3 条
EXPECTED
log "全部通过，输出在 $OUT"
