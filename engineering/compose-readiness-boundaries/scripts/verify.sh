#!/usr/bin/env bash
# Compose 里的依赖就绪、网络与项目边界：depends_on 默认条件与 service_healthy、不健康的依赖、端口发布的绑定地址、EXPOSE 与内部可达性、服务名的解析范围、项目名隔离与 down / down -v 的删除范围（Docker Compose v5）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 alpine 容器；Compose 项目名以 csl-crb- 开头，外部卷 csl-crb-shared，结束时只删除这些；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
cleanup() {
  for p in started healthy unhealthy ports p1 p2; do
    for y in src/*.yaml; do docker compose -p "csl-crb-$p" -f "$y" down -v --remove-orphans >/dev/null 2>&1 || true; done
  done
  docker volume rm csl-crb-shared >/dev/null 2>&1 || true
}
trap 'rc=$?; cleanup; exit $rc' EXIT
cleanup
f="$OUT/output.tsv"; : >"$f"
o() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
dc() { local p="$1" y="$2"; shift 2; docker compose -p "csl-crb-$p" -f "src/$y.yaml" "$@"; }
applog() { for _ in $(seq 1 20); do l=$(dc "$1" "$2" logs --no-log-prefix app 2>/dev/null | head -1); [ -n "$l" ] && { echo "$l"; return; }; sleep 0.5; done; echo none; }

# 一、depends_on 的默认条件
dc started started up -d >/dev/null 2>&1
o "depends.started" "depends_on 默认条件（service_started），db 5 秒后才监听：app 启动时连接 db 的结果 $(applog started started)"
t0=$(date +%s)
dc healthy healthy up -d >/dev/null 2>&1
t1=$(date +%s)
o "depends.healthy" "condition: service_healthy，db 配了健康检查：up 用时 4 到 15 秒之间=$([ $((t1 - t0)) -ge 4 ] && [ $((t1 - t0)) -le 15 ] && echo true || echo false)，app 的连接结果 $(applog healthy healthy)"
if dc unhealthy unhealthy up -d >"$OUT/unhealthy.txt" 2>&1; then u=0; else u=$?; fi
o "depends.unhealthy" "db 一直不健康：up 退出码非 0=$([ "$u" -ne 0 ] && echo true || echo false)，app 容器状态 $(docker inspect -f '{{.State.Status}}' "$(dc unhealthy unhealthy ps -aq app)" 2>/dev/null || echo 不存在)"

# 二、端口发布与 EXPOSE
dc ports ports up -d >/dev/null 2>&1
sleep 1
WEB=$(dc ports ports ps -q web)
o "ports.binding" "发布 127.0.0.1:18080:80 与 18081:80：绑定地址 $(docker inspect -f '{{range $p, $b := .NetworkSettings.Ports}}{{range $b}}{{.HostIp}}:{{.HostPort}} {{end}}{{end}}' "$WEB" | tr ' ' '\n' | grep -v '^$' | grep -v '::' | sort | tr '\n' ' ' | sed 's/ $//')"
o "ports.expose" "镜像与 Compose 都没有声明 9000：同一网络的 peer 访问 web:9000 得到 $(dc ports ports exec -T peer sh -c 'nc -w 2 web 9000' 2>/dev/null || echo 失败)（EXPOSE 只是元数据，不控制可达性）；容器的 EXPOSE 列表 $(docker inspect -f '{{json .Config.ExposedPorts}}' "$WEB")（80 是发布端口时自动加上的）"
o "dns.scope" "web 的服务名：同一项目里的 peer 能解析=$(dc ports ports exec -T peer sh -c 'nslookup web >/dev/null 2>&1 && echo true || echo false')；另一个项目的容器能解析=$(dc healthy healthy exec -T app sh -c 'nslookup web >/dev/null 2>&1 && echo true || echo false')"

# 三、项目名隔离与删除范围
docker volume create csl-crb-shared >/dev/null
dc p1 data up -d >/dev/null 2>&1; dc p2 data up -d >/dev/null 2>&1; sleep 1
o "project.isolation" "同一个文件用两个项目名启动：卷 $(docker volume ls -q | grep -E '^csl-crb-p[12]_' | sort | tr '\n' ' ' | sed 's/ $//')，网络 $(docker network ls --format '{{.Name}}' | grep -E '^csl-crb-p[12]_' | sort | tr '\n' ' ' | sed 's/ $//')"
dc p1 data down >/dev/null 2>&1
o "down" "p1 执行 down：p1 的卷还在=$(docker volume inspect csl-crb-p1_data >/dev/null 2>&1 && echo true || echo false)，p1 的网络还在=$(docker network inspect csl-crb-p1_default >/dev/null 2>&1 && echo true || echo false)"
dc p1 data down -v >/dev/null 2>&1; dc p2 data down -v >/dev/null 2>&1
o "down.v" "两个项目都执行 down -v：项目声明的卷还在=$(docker volume ls -q | grep -cE '^csl-crb-p[12]_' || true) 个，外部卷 csl-crb-shared 还在=$(docker volume inspect csl-crb-shared >/dev/null 2>&1 && echo true || echo false)"
write_environment "$OUT/environment.txt" "docker: $(docker version --format '{{.Server.Version}}')" "compose: $(docker compose version --short)"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
depends.started	depends_on 默认条件（service_started），db 5 秒后才监听：app 启动时连接 db 的结果 refused
depends.healthy	condition: service_healthy，db 配了健康检查：up 用时 4 到 15 秒之间=true，app 的连接结果 connected
depends.unhealthy	db 一直不健康：up 退出码非 0=true，app 容器状态 created
ports.binding	发布 127.0.0.1:18080:80 与 18081:80：绑定地址 0.0.0.0:18081 127.0.0.1:18080
ports.expose	镜像与 Compose 都没有声明 9000：同一网络的 peer 访问 web:9000 得到 p9000（EXPOSE 只是元数据，不控制可达性）；容器的 EXPOSE 列表 {"80/tcp":{}}（80 是发布端口时自动加上的）
dns.scope	web 的服务名：同一项目里的 peer 能解析=true；另一个项目的容器能解析=false
project.isolation	同一个文件用两个项目名启动：卷 csl-crb-p1_data csl-crb-p2_data，网络 csl-crb-p1_default csl-crb-p2_default
down	p1 执行 down：p1 的卷还在=true，p1 的网络还在=false
down.v	两个项目都执行 down -v：项目声明的卷还在=0 个，外部卷 csl-crb-shared 还在=true
EXPECTED
log "全部通过，输出在 ${OUT}"
