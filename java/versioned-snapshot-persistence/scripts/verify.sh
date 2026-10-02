#!/usr/bin/env bash
# 内存状态加后台保存：先清脏标记、成功后清脏标记、逐字段读取可变对象、版本化不可变快照、乱序完成的旧写入
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/SnapshotLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
expect_line "$f" "env	java.version=25.0.4" "运行在 JDK 25.0.4"
expect_line "$f" "clear_before	修改为 v1，第一次保存失败，之后又跑了两轮：dirty=false，存储里是 online=60 offline=40 note=v0，共调用存储 1 次" "先清标记：失败的修改不再被保存"
expect_line "$f" "clear_after	v1 保存期间修改为 v2，保存成功后清标记：dirty=false，存储里是 online=60 offline=40 note=v1，之后两轮触发保存 0 次" "成功后清标记：保存期间的修改被一起清掉"
expect_line "$f" "torn	保存线程读到一半时发生了「线上挪 30 到线下」：存储里是 online=60 offline=70 note=v0，两项之和 130（内存里始终是 100）" "逐字段读取：存下来的内容自相矛盾"
expect_line "$f" "versioned.retry	修改为 v1，第一次保存失败：已保存版本 0；下一轮重试后已保存版本 1，存储里是 online=30 offline=70 note=v1" "版本化：失败不前进，下一轮重试"
expect_line "$f" "versioned.during_save	第 2 版保存期间又修改为第 3 版：保存完成时已保存版本 2，内存版本 3；下一轮之后已保存版本 3，存储里是 online=20 offline=80 note=v3" "版本化：保存期间的修改下一轮继续保存"
expect_line "$f" "stale.plain	第 5 版先写入、第 4 版的写入后到，存储不比较版本：存储里是 online=10 offline=90 note=v4" "不比较版本：旧内容覆盖新内容"
expect_line "$f" "stale.guarded	同样的顺序，存储只接受更新的版本：两次写入分别返回 true、false，存储里是 online=0 offline=100 note=v5" "按版本条件写入：旧写入被拒绝"
log "全部通过，输出在 $OUT"
