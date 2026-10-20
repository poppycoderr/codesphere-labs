#!/usr/bin/env bash
# 让模型产出数据而不是代码：逗号切分、BOM、引号与换行字段、Schema 校验、单事务导入与吞异常的假成功（Python 3.14、SQLite）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm --network=none -v "$PWD/src:/src:ro" "$PY" python /src/lab.py >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
# 输入固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
naive.summary	逗号切分 + 逐行提交 + 吞异常：报告成功 4 行、失败 4 行，脚本没有报错退出
naive.rows	库里实际的行：1|Ada|active|"负责 A；2|Bo|active|"第一行；3|Cy|on_leave|；4|Di|P|把 P 写成了状态
bom.utf8	按 utf-8 解码后用 DictReader：第一列的列名是 '\ufeffid'，取 row['id'] 得到 None
bom.sig	按 utf-8-sig 解码：列名 ['id', 'name', 'dept', 'status', 'note']
csv.quoted	标准 csv 解析：共 6 条记录；第 1 条 note='负责 A,B 两个模块'，第 2 条 note='第一行\n第二行'
validate	Schema 校验：3 条合格，3 个问题：第 5 行 status='P' 不在 ['active', 'left', 'on_leave']；第 6 行 id=3 重复；第 7 行 dept='sales' 不存在
atomic.all	不过滤、整批单事务导入：回滚（IntegrityError: UNIQUE constraint failed: emp.id），库里 0 行
atomic.valid	只导入合格记录（导入前展示问题清单）：已提交，库里 3 行
EXPECTED
