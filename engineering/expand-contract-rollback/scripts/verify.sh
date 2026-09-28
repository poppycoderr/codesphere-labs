#!/usr/bin/env bash
# 发布的可回退性：原地改名与扩展—迁移—收缩在新旧版本并存、回退时的表现；旧消费者读取新版本事件
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器与 JDK 21 容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-expand-contract
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
CP=""
for c in "$MYSQL_JDBC_JAR_COORD" com.fasterxml.jackson.core:jackson-databind:2.22.3 com.fasterxml.jackson.core:jackson-core:2.22.3 com.fasterxml.jackson.core:jackson-annotations:2.22; do
  CP="$CP:/cache/m2/$(basename "$(maven_jar "$c")")"
done
java_in_network "$CID" -cp "${CP#:}" src/Rollback.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jackson-databind: 2.22.3"
f="$OUT/output.tsv"
cat "$f" >&2
expect_line "$f" "rename.before	v1 成功 20、失败 0" "改名前 v1 正常"
expect_regex "$f" "^rename\.rolling	v1 成功 0、失败 20（Unknown column 'phone'.*）；v2 成功 20、失败 0$" "原地改名：滚动发布期间旧实例全部失败"
expect_regex "$f" "^rename\.rollback	v1 成功 0、失败 20（Unknown column 'phone'" "原地改名：回退到 v1 后全部失败"
expect_line "$f" "ec.expand	v1 成功 20、失败 0" "扩展：只加列，v1 不受影响"
expect_line "$f" "ec.dual_write_rolling	v1 成功 20、失败 0；v1.5 成功 20、失败 0" "双写版本与旧版本并存"
expect_line "$f" "ec.read_new_rolling	v1.5 成功 20、失败 0；v2 成功 20、失败 0" "读新列的 v2 与 v1.5 并存"
expect_line "$f" "ec.rollback_to_v1_5	v1.5 成功 20、失败 0" "回退到 v1.5"
expect_line "$f" "ec.rollback_to_v1	v1 成功 20、失败 0" "回退到 v1"
expect_line "$f" "ec.consistency_after_rollback	phone 与 mobile 不一致的行 20，mobile 为空的行 20" "回退期间 v1 写入的行 mobile 为空，需要再次回填"
expect_line "$f" "ec.consistency_before_contract	phone 与 mobile 不一致的行 0，mobile 为空的行 0" "收缩前再次回填后一致"
expect_line "$f" "ec.after_contract	v2 成功 20、失败 0" "收缩后 v2 正常"
expect_regex "$f" "^ec\.rollback_after_contract	v1 成功 0、失败 20（Unknown column 'phone'" "收缩之后不能再回退到 v1"
expect_regex "$f" "^event\.added\.strict	解析失败：UnrecognizedPropertyException" "新增字段：严格解析失败"
expect_regex "$f" "^event\.added\.tolerant	解析成功：RegisteredV1\[id=1, attendee=a, phone=13800000001\]" "新增字段：宽容解析成功"
expect_regex "$f" "^event\.renamed\.tolerant	解析成功：RegisteredV1\[id=2, attendee=b, phone=null\]" "改名字段：宽容解析「成功」但手机号丢了"
python3 - "$f" <<'PY2'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
ok = {(v, sc): int(n) for v, sc, n in re.findall(r"^matrix\.(\S+)\.(\S+)\t\S+ 成功 (\d+)", t, re.M)}
expect = {("v1", "phone_only"): 20, ("v1", "both"): 20, ("v1", "mobile_only"): 0,
          ("v1.5", "both"): 20, ("v2_dual", "both"): 20,
          ("v2_only", "phone_only"): 0, ("v2_only", "both"): 20, ("v2_only", "mobile_only"): 20}
for k, v in expect.items():
    assert ok[k] == v, (k, ok[k], v)
print("通过：版本 × 表结构矩阵与预期一致")
PY2
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
