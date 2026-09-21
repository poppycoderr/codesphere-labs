#!/usr/bin/env bash
# 删除本实验的容器、网络和匿名卷（只影响 compose 项目 csl-order-by-limit）
source "$(dirname "$0")/env.sh"
"${COMPOSE[@]}" down -v --remove-orphans
