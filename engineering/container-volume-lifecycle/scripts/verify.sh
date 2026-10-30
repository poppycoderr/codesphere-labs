#!/usr/bin/env bash
# 容器重建之后，数据和初始化还在吗：匿名卷与命名卷、只在空数据目录执行的初始化与密码、绑定挂载遮住镜像内容、新命名卷被镜像内容填充、tmpfs（Docker 29、MySQL 8.4.11）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 mysql:8.4.11 与 alpine 容器；所有容器与卷以 csl-cvl- 开头，结束时只删除本实验创建的；约 2 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
MY="mysql:8.4.11@sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a"
AL="alpine:3.22@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f"
ANON=()
cleanup() {
  docker ps -aq --filter name=csl-cvl- | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter name=csl-cvl- | xargs -r docker volume rm >/dev/null 2>&1 || true
  for v in "${ANON[@]:-}"; do [ -n "$v" ] && docker volume rm "$v" >/dev/null 2>&1 || true; done
  rm -rf "$PWD/build/bind"
}
trap 'rc=$?; cleanup; exit $rc' EXIT
cleanup
f="$OUT/output.tsv"; : >"$f"
o() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
wait_mysql() {
  for _ in $(seq 1 90); do
    docker exec "$1" mysqladmin ping -h 127.0.0.1 -uroot -p"$2" --silent >/dev/null 2>&1 && docker exec "$1" mysql -h 127.0.0.1 -uroot -p"$2" -e "SELECT 1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
q() { docker exec "$1" mysql -h 127.0.0.1 -uroot -p"$2" -N -e "$3" 2>/dev/null; }
run_mysql() {  # 名字 密码 额外参数...
  local n="$1" pw="$2"; shift 2
  docker run -d --name "$n" -e MYSQL_ROOT_PASSWORD="$pw" -e MYSQL_DATABASE=labs -v "$PWD/src/initdb:/docker-entrypoint-initdb.d:ro" "$@" "$MY" --skip-log-bin >/dev/null
}

# 一、匿名卷：镜像声明了 VOLUME，没有指定卷时自动创建
run_mysql csl-cvl-a1 example_password
wait_mysql csl-cvl-a1 example_password
q csl-cvl-a1 example_password "INSERT INTO labs.seed VALUES (2, 'order-2')"
v1=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' csl-cvl-a1); ANON+=("$v1")
docker rm -f csl-cvl-a1 >/dev/null
run_mysql csl-cvl-a2 example_password
wait_mysql csl-cvl-a2 example_password
v2=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' csl-cvl-a2); ANON+=("$v2")
o "anon" "镜像在 /var/lib/mysql 上声明了 VOLUME：同样的 docker run 删掉重建后，第 2 行还在=$( [ "$(q csl-cvl-a2 example_password 'SELECT COUNT(*) FROM labs.seed WHERE id = 2')" = 1 ] && echo true || echo false)；两次用的是同一个卷=$([ "$v1" = "$v2" ] && echo true || echo false)；旧的匿名卷仍存在=$(docker volume inspect "$v1" >/dev/null 2>&1 && echo true || echo false)"
docker rm -f csl-cvl-a2 >/dev/null

# 二、命名卷：重建后数据还在
docker volume create csl-cvl-data >/dev/null
run_mysql csl-cvl-n1 example_password -v csl-cvl-data:/var/lib/mysql
wait_mysql csl-cvl-n1 example_password
q csl-cvl-n1 example_password "INSERT INTO labs.seed VALUES (2, 'order-2')"
first_init=$(docker logs csl-cvl-n1 2>&1 | grep -c 'running /docker-entrypoint-initdb.d' || true)
docker rm -f csl-cvl-n1 >/dev/null
# 三、同一个卷上换一个密码、换一份初始化脚本重新启动
run_mysql csl-cvl-n2 another_example_password -v csl-cvl-data:/var/lib/mysql
sleep 2
if wait_mysql csl-cvl-n2 example_password; then old=true; else old=false; fi
new=$(docker exec csl-cvl-n2 mysql -h 127.0.0.1 -uroot -panother_example_password -e "SELECT 1" >/dev/null 2>&1 && echo true || echo false)
o "named" "命名卷：删掉容器重建后第 2 行还在=$( [ "$(q csl-cvl-n2 example_password 'SELECT COUNT(*) FROM labs.seed WHERE id = 2')" = 1 ] && echo true || echo false)"
o "init.password" "在已有数据的卷上把 MYSQL_ROOT_PASSWORD 改成新值再启动：旧密码能登录=${old}，新密码能登录=$new"
o "init.scripts" "首次启动（空数据目录）时日志里 initdb 执行记录 ${first_init} 次；第二次启动时初始化脚本没有再执行：seed 表里 from-initdb 只有 $(q csl-cvl-n2 example_password "SELECT COUNT(*) FROM labs.seed WHERE note = 'from-initdb'") 行；日志里出现 initdb 执行记录 $(docker logs csl-cvl-n2 2>&1 | grep -c 'running /docker-entrypoint-initdb.d' || true) 次"
docker logs csl-cvl-n2 2>&1 | grep -iE "initdb|Database files are|Initializ" | sed -E 's/^[0-9T:.Z-]+ //' | head -5 >"$OUT/second-start.log"
docker rm -f csl-cvl-n2 >/dev/null

# 四、挂载到镜像里已有内容的目录
mkdir -p "$PWD/build/bind"
bind=$(docker run --rm -v "$PWD/build/bind:/etc/apk" "$AL" sh -c 'ls /etc/apk | wc -l')
named=$(docker run --rm -v csl-cvl-apk:/etc/apk "$AL" sh -c 'ls /etc/apk | wc -l')
image=$(docker run --rm "$AL" sh -c 'ls /etc/apk | wc -l')
o "mount.shadow" "镜像的 /etc/apk 里有 $image 个条目：挂载空的宿主机目录后看到 $bind 个（被遮住）；挂载一个新的命名卷看到 $named 个（空卷首次挂载时由镜像内容填充）"
docker run --rm -v csl-cvl-apk:/etc/apk "$AL" sh -c 'echo changed > /etc/apk/arch'
again=$(docker run --rm -v csl-cvl-apk:/etc/apk "$AL" cat /etc/apk/arch)
o "mount.stale" "在命名卷里改了 arch 文件后再挂载：读到「${again}」（卷里的内容不会随镜像更新）"

# 五、tmpfs
docker run -d --name csl-cvl-t --tmpfs /scratch "$AL" sleep 300 >/dev/null
docker exec csl-cvl-t sh -c 'echo x > /scratch/f; echo y > /root/f'
docker restart -t 0 csl-cvl-t >/dev/null
o "tmpfs" "容器重启后：tmpfs 里的文件还在=$(docker exec csl-cvl-t test -f /scratch/f && echo true || echo false)，可写层里的文件还在=$(docker exec csl-cvl-t test -f /root/f && echo true || echo false)"
docker rm -f csl-cvl-t >/dev/null
write_environment "$OUT/environment.txt" "mysql_image: $MY" "alpine_image: $AL" "docker: $(docker version --format '{{.Server.Version}}')"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
anon	镜像在 /var/lib/mysql 上声明了 VOLUME：同样的 docker run 删掉重建后，第 2 行还在=false；两次用的是同一个卷=false；旧的匿名卷仍存在=true
named	命名卷：删掉容器重建后第 2 行还在=true
init.password	在已有数据的卷上把 MYSQL_ROOT_PASSWORD 改成新值再启动：旧密码能登录=true，新密码能登录=false
init.scripts	首次启动（空数据目录）时日志里 initdb 执行记录 1 次；第二次启动时初始化脚本没有再执行：seed 表里 from-initdb 只有 1 行；日志里出现 initdb 执行记录 0 次
mount.shadow	镜像的 /etc/apk 里有 5 个条目：挂载空的宿主机目录后看到 0 个（被遮住）；挂载一个新的命名卷看到 5 个（空卷首次挂载时由镜像内容填充）
mount.stale	在命名卷里改了 arch 文件后再挂载：读到「changed」（卷里的内容不会随镜像更新）
tmpfs	容器重启后：tmpfs 里的文件还在=false，可写层里的文件还在=true
EXPECTED
log "全部通过，输出在 ${OUT}"
