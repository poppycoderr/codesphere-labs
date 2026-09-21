#!/usr/bin/env bash
# 启动固定版本的 MySQL 容器（2 CPU、2 GB 内存，不暴露端口）
source "$(dirname "$0")/env.sh"
log "启动 MySQL 8.4.11 容器（compose 项目 csl-order-by-limit）"
"${COMPOSE[@]}" up -d --wait
