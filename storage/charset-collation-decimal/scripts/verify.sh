#!/usr/bin/env bash
# 值进库之前经过的转换：utf8mb3 与四字节字符、客户端声明的字符集与实际字节不一致、排序规则对比较与唯一约束的影响、DECIMAL 的舍入与越界（MySQL 8.4.11）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：labs 共享的 MySQL 8.4.11 容器（项目名 csl-charset-collation），结束时删除；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
P=csl-charset-collation
trap 'rc=$?; mysql_down "$P" >/dev/null 2>&1 || true; exit $rc' EXIT
mysql_up "$P" >/dev/null 2>&1
U=(--default-character-set=utf8mb4)
mysql_exec "$P" labs -N "${U[@]}" <schema/main.sql >"$OUT/raw.tsv" 2>"$OUT/main-errors.txt"
: >"$OUT/errors.txt"
while IFS= read -r stmt; do
  case "$stmt" in --*|"") continue ;; esac
  printf '%s\t%s\n' "$stmt" "$(mysql_exec "$P" labs -N "${U[@]}" -e "$stmt" </dev/null 2>&1 | grep -v Warning | head -1)" >>"$OUT/errors.txt"
done <schema/errors.sql
# 客户端声明 latin1、实际发送 UTF-8 字节：服务端把每个字节当成一个 latin1 字符再转成 utf8mb4
mysql_exec "$P" labs "${U[@]}" -e "DROP TABLE IF EXISTS greet; CREATE TABLE greet (id INT PRIMARY KEY, s VARCHAR(20)) CHARACTER SET utf8mb4;"
mysql_exec "$P" labs --default-character-set=utf8mb4 -e "INSERT INTO greet VALUES (1, '你好')"
mysql_exec "$P" labs --default-character-set=latin1 -e "INSERT INTO greet VALUES (2, '你好')"
{
  printf 'mis.hex\t%s\n' "$(mysql_exec "$P" labs -N "${U[@]}" -e "SELECT GROUP_CONCAT(CONCAT(id, ':', HEX(s), ':', CHAR_LENGTH(s)) ORDER BY id) FROM greet")"
  printf 'mis.read.latin1\t%s\n' "$(mysql_exec "$P" labs -N --default-character-set=latin1 -e "SELECT s FROM greet WHERE id = 2")"
  printf 'mis.read.utf8\t%s\n' "$(mysql_exec "$P" labs -N "${U[@]}" -e "SELECT s FROM greet WHERE id = 2")"
  printf 'mis.where\t%s\n' "$(mysql_exec "$P" labs -N "${U[@]}" -e "SELECT COUNT(*) FROM greet WHERE s = '你好'")"
} >>"$OUT/raw.tsv"
f="$OUT/output.tsv"
python3 - "$OUT/raw.tsv" "$OUT/errors.txt" >"$f" <<'PY'
import sys
v = {}
for l in open(sys.argv[1], encoding="utf-8"):
    if "\t" in l and not l.startswith(("Note", "Warning")):
        k, val = l.rstrip("\n").split("\t", 1); v[k] = val
e = dict(l.rstrip("\n").split("\t", 1) for l in open(sys.argv[2], encoding="utf-8"))
def err(prefix):
    val = next(val for k, val in e.items() if k.startswith(prefix))
    code = val.split(" (")[0]
    msg = val.split(": ", 1)[1] if ": " in val else ""
    return f"{code}（{msg}）"
print(f"mb3.emoji\t向 utf8mb3 列写入「好的👍」：{err('INSERT INTO t3')}")
c, b, h = v["mb4.len"].split("|"); print(f"mb4.len\t同样的值写入 utf8mb4 列：{c} 个字符、{b} 个字节，HEX {h}")
a, b, c, d = v["mis.hex"].split(",")[0].split(":")[1], v["mis.hex"].split(",")[1].split(":")[1], v["mis.hex"].split(",")[0].split(":")[2], v["mis.hex"].split(",")[1].split(":")[2]
print(f"mis.bytes\t同样输入「你好」：客户端声明 utf8mb4 时存为 {a}（{c} 个字符）；声明 latin1 时存为 {b}（{d} 个字符）")
print(f"mis.read\t第二行用 latin1 客户端读回：{v['mis.read.latin1']}；用 utf8mb4 客户端读回：{v['mis.read.utf8']}；utf8mb4 客户端查 WHERE s = '你好' 匹配 {v['mis.where']} 行（只有第一行）")
srv, db = v["coll.default"].split("|"); print(f"coll.default\t服务器默认排序规则 {srv}，库 labs 的默认排序规则 {db}")
a, b, c, d = v["coll.ai_ci"].split("|"); print(f"coll.compare\t'jose' 与 'José'：0900_ai_ci 相等={a == '1'}，'jose' 与 'JOSE' 相等={b == '1'}；0900_as_cs 相等={c == '1'}；utf8mb4_bin 相等={d == '1'}")
a, b, pa, pb = v["coll.pad"].split("|"); print(f"coll.pad\t'a' 与 'a '：0900_ai_ci（{pa}）相等={a == '1'}，general_ci（{pb}）相等={b == '1'}")
print(f"coll.unique\tai_ci 的主键上已有 'José'，再插入 'jose'：{err('INSERT INTO users_ci')}；utf8mb4_bin 的主键上 José、jose、JOSE 共 {v['coll.bin.count']} 行")
a, b = v["coll.lookup"].split("|"); print(f"coll.lookup\tWHERE name = 'jose'：ai_ci 表匹配 {a} 行（存的是 José），bin 表匹配 {b} 行")
amt, code = v["dec.round"].split("|"); print(f"dec.round\tDECIMAL(10,2) 写入 1.005：存为 {amt}，诊断 {code}（Note，不是错误）")
print(f"dec.range\tDECIMAL(10,2) 写入 123456789.12（整数部分 9 位，上限 8 位）：{err('INSERT INTO money')}")
print(f"dec.max\tDECIMAL(66,2)：{err('CREATE TABLE too_wide')}")
q, inc, back = v["dec.div"].split("|"); print(f"dec.div\tDECIMAL 的 1/3 = {q}（div_precision_increment = {inc}），再乘 3 = {back}")
a, b = v["dec.sum"].split("|"); print(f"dec.sum\t0.1 + 0.2 = 0.3：DECIMAL 为 {a == '1'}，DOUBLE 为 {b == '1'}")
PY
mysql_exec "$P" labs -N -e "SELECT VERSION()" | sed 's/^/mysql: /' >"$OUT/version.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
mb3.emoji	向 utf8mb3 列写入「好的👍」：ERROR 1366（Incorrect string value: '\xF0\x9F\x91\x8D' for column 's' at row 1）
mb4.len	同样的值写入 utf8mb4 列：3 个字符、10 个字节，HEX E5A5BDE79A84F09F918D
mis.bytes	同样输入「你好」：客户端声明 utf8mb4 时存为 E4BDA0E5A5BD（2 个字符）；声明 latin1 时存为 C3A4C2BDC2A0C3A5C2A5C2BD（6 个字符）
mis.read	第二行用 latin1 客户端读回：你好；用 utf8mb4 客户端读回：ä½ å¥½；utf8mb4 客户端查 WHERE s = '你好' 匹配 1 行（只有第一行）
coll.default	服务器默认排序规则 utf8mb4_0900_ai_ci，库 labs 的默认排序规则 utf8mb4_0900_ai_ci
coll.compare	'jose' 与 'José'：0900_ai_ci 相等=True，'jose' 与 'JOSE' 相等=True；0900_as_cs 相等=False；utf8mb4_bin 相等=False
coll.pad	'a' 与 'a '：0900_ai_ci（NO PAD）相等=False，general_ci（PAD SPACE）相等=True
coll.unique	ai_ci 的主键上已有 'José'，再插入 'jose'：ERROR 1062（Duplicate entry 'jose' for key 'users_ci.PRIMARY'）；utf8mb4_bin 的主键上 José、jose、JOSE 共 3 行
coll.lookup	WHERE name = 'jose'：ai_ci 表匹配 1 行（存的是 José），bin 表匹配 1 行
dec.round	DECIMAL(10,2) 写入 1.005：存为 1.01，诊断 1265（Note，不是错误）
dec.range	DECIMAL(10,2) 写入 123456789.12（整数部分 9 位，上限 8 位）：ERROR 1264（Out of range value for column 'amount' at row 1）
dec.max	DECIMAL(66,2)：ERROR 1426（Too-big precision 66 specified for 'a'. Maximum is 65.）
dec.div	DECIMAL 的 1/3 = 0.333333（div_precision_increment = 4），再乘 3 = 1.000000
dec.sum	0.1 + 0.2 = 0.3：DECIMAL 为 True，DOUBLE 为 False
EXPECTED
log "全部通过，输出在 ${OUT}"
