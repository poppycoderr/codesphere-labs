#!/usr/bin/env bash
# 同一个报名需求的事务脚本与领域模型实现，各经历三次需求变化（候补、调换场次、VIP 保留席位）：验收场景、改动的文件与行数、名额条件出现的位置数
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 20 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" sh -c 'rm -rf build/classes && mkdir -p build/classes/api && javac -d build/classes/api code/api/Api.java && java -Duser.timezone=UTC -cp build/classes/api src/EvolutionLab.java' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
script.v0	非空行 19，名额条件写了 1 处；验收场景 2/2 通过
script.v1	非空行 23，名额条件写了 1 处；相对上一版改动 1 个文件，+9 −5 行；验收场景 2/2 通过
script.v2	非空行 31，名额条件写了 2 处；相对上一版改动 1 个文件，+9 −1 行；验收场景 4/4 通过
script.v3	非空行 43，名额条件写了 3 处；相对上一版改动 1 个文件，+16 −4 行；验收场景 7/7 通过
model.v0	非空行 30，名额条件写了 1 处；验收场景 2/2 通过
model.v1	非空行 37，名额条件写了 1 处；相对上一版改动 1 个文件，+11 −4 行；验收场景 2/2 通过
model.v2	非空行 50，名额条件写了 2 处；相对上一版改动 2 个文件，+14 −1 行；验收场景 4/4 通过
model.v3	非空行 61，名额条件写了 1 处；相对上一版改动 2 个文件，+28 −17 行；验收场景 7/7 通过
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
