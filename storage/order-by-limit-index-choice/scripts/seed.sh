#!/usr/bin/env bash
# 建表并确定性生成 300 万行数据；已生成过则跳过（FORCE_SEED=1 强制重建）
source "$(dirname "$0")/env.sh"
rows=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='labs' AND table_name='task_event'" | sql -N)
if [ "$rows" = 1 ] && [ "${FORCE_SEED:-0}" != 1 ]; then
  n=$(echo "SELECT COUNT(*) FROM task_event" | sql -N)
  [ "$n" = 3000000 ] && { log "数据已存在（$n 行），跳过造数"; exit 0; }
fi
log "建表并生成 3,000,000 行（约 1—2 分钟）"
start=$(date +%s)
sql <schema/01-schema.sql
sql <schema/02-seed.sql
sql <schema/03-statistics.sql >/dev/null
log "造数完成，用时 $(( $(date +%s) - start )) 秒"
