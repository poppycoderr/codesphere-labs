#!/usr/bin/env bash
# 完整流程：启动容器 → 造数 → 采集现场 → 预热与采样 → 汇总与断言
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：2 CPU、2 GB 内存、约 1 GB 磁盘；不占用宿主机端口；首次运行约 1 分钟（不含拉取镜像）
source "$(dirname "$0")/env.sh"
OUT="${1:-build/run}"
rm -rf "${OUT:?}/before" "$OUT/after" "$OUT/control"
mkdir -p "$OUT"
scripts/setup.sh
scripts/seed.sh
scripts/collect.sh "$OUT"
scripts/run.sh "$OUT"
write_environment "$OUT/environment.txt"
python3 scripts/summarize.py "$OUT" | tee "$OUT/assertions.txt"
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
