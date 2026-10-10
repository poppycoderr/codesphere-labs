#!/usr/bin/env bash
# LSM 树的放大：顺序键与随机键的写放大、覆盖写留下的旧版本、删除之后空间何时回收（RocksDB，关闭压缩）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，数据写在容器内的临时目录（约 1 GB，容器退出即删除）；约 2 分钟。只断言范围
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORD="org.rocksdb:rocksdbjni:11.1.2"
JAR="/cache/m2/$(basename "$(maven_jar "$COORD")")"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java --enable-native-access=ALL-UNNAMED -cp "$JAR" src/LsmLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "library: $COORD"
cat "$f" >&2
val() { awk -F'\t' -v k="$1" '$1==k {print $2}' "$f"; }
between() { awk -v a="$1" -v lo="$2" -v hi="$3" 'BEGIN{exit !(a>=lo && a<=hi)}' || fail "$4（$1 不在 $2—$3）"; log "通过：$4（$1 在 $2—$3）"; }
between "$(val sequential_leveled.write_amplification)" 0.9 1.3 "顺序键：写放大约 1（不含预写日志）"
between "$(val random_leveled.write_amplification)" 2.5 8 "随机键：写放大在 2.5 以上"
between "$(val overwrite.sst_before_compaction_mb)" 150 200 "同一批键写 5 遍、未压实：磁盘上约 5 份"
between "$(val overwrite.sst_after_compaction_mb)" 30 45 "压实之后：约 1 份"
between "$(val delete.sst_after_delete_mb)" 37 50 "全部删除之后、压实之前：文件比删除前还大"
[ "$(val delete.visible_keys)" = "0" ] || fail "删除之后不应再读到键"
between "$(val delete.sst_after_compaction_mb)" 0 1 "删除并压实之后：空间回收"
log "全部通过，输出在 $OUT"
