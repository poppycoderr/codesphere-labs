#!/usr/bin/env bash
# 容器的边界：共享内核、namespace（含默认不隔离的 user namespace）、cgroup v2 限额与进程看到的资源、OOM、可写层与卷、PID 1 与 SIGTERM
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker；只创建本实验前缀为 csl-cb- 的容器与卷，结束时删除；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
ALPINE="alpine:3.24.2@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6"
BUSYBOX="busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e"
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
cleanup() { docker rm -f csl-cb-ns csl-cb-oom csl-cb-layer csl-cb-pid1 csl-cb-init csl-cb-java >/dev/null 2>&1 || true; docker volume rm -f csl-cb-data >/dev/null 2>&1 || true; }
cleanup
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }

# 1. 内核：不同镜像里看到的是同一个内核
out kernel "alpine 里 uname -r：$(docker run --rm "$ALPINE" uname -r)；busybox 里 uname -r：$(docker run --rm "$BUSYBOX" uname -r)；temurin（Ubuntu）里 uname -r：$(docker run --rm "$J25" uname -r)"

# 2. namespace：从宿主机（Docker 的 Linux 虚拟机）视角比较宿主机 PID 1 与容器进程
docker run -d --name csl-cb-ns "$ALPINE" sleep 300 >/dev/null
CPID=$(docker inspect -f '{{.State.Pid}}' csl-cb-ns)
docker run --rm --privileged --pid=host "$ALPINE" sh -c "
  for ns in pid net mnt uts ipc cgroup user; do
    h=\$(readlink /proc/1/ns/\$ns); c=\$(readlink /proc/$CPID/ns/\$ns)
    if [ \"\$h\" = \"\$c\" ]; then echo \"ns.\$ns	与宿主机相同（\$c）\"; else echo \"ns.\$ns	与宿主机不同\"; fi
  done
  echo \"ns.uid	容器进程在宿主机上的 uid：\$(awk '/^Uid:/ {print \$2}' /proc/$CPID/status)\"
" >>"$f"
out ns.pid_inside "容器里看到的进程：$(docker exec csl-cb-ns ps -o pid,comm | tail -n +2 | awk '{print $1":"$2}' | tr '\n' ' ')"
docker rm -f csl-cb-ns >/dev/null

# 3. cgroup v2：限额写在哪里，进程看到的资源是什么
lim=(--memory 256m --cpus 0.5)
out cgroup.files "$(docker run --rm "${lim[@]}" "$ALPINE" sh -c 'echo "memory.max=$(cat /sys/fs/cgroup/memory.max) cpu.max=$(cat /sys/fs/cgroup/cpu.max)"')"
out cgroup.tools "$(docker run --rm "${lim[@]}" "$ALPINE" sh -c 'echo "nproc=$(nproc) /proc/meminfo MemTotal=$(awk "/MemTotal/ {print int(\$2/1024)}" /proc/meminfo)MB"')"
out cgroup.jvm "$(docker run --rm "${lim[@]}" -v "$PWD/src:/src:ro" "$J25" java /src/Resources.java)"
out cgroup.jvm_1g "$(docker run --rm --memory 1g --cpus 2 -v "$PWD/src:/src:ro" "$J25" java /src/Resources.java)"
out cgroup.jvm_unlimited "$(docker run --rm -v "$PWD/src:/src:ro" "$J25" java /src/Resources.java)"

# 4. OOM：超过 memory.max 的进程被内核杀掉
docker run --name csl-cb-oom --memory 64m --memory-swap 64m "$ALPINE" sh -c 'a=x; while true; do a="$a$a"; done' >/dev/null 2>&1 || true
out oom "退出码 $(docker inspect -f '{{.State.ExitCode}}' csl-cb-oom)，OOMKilled=$(docker inspect -f '{{.State.OOMKilled}}' csl-cb-oom)"
docker rm -f csl-cb-oom >/dev/null

# 5. 可写层与卷
docker volume create csl-cb-data >/dev/null
docker run --name csl-cb-layer -v csl-cb-data:/data "$ALPINE" sh -c 'echo order > /tmp/in-layer.txt; echo order > /data/in-volume.txt'
out layer.diff "docker diff：$(docker diff csl-cb-layer | tr '\n' ' ')"
docker rm -f csl-cb-layer >/dev/null
out layer.after_rm "删除容器后新容器里：/tmp/in-layer.txt $(docker run --rm "$ALPINE" sh -c 'test -f /tmp/in-layer.txt && echo 存在 || echo 不存在')，卷里的 in-volume.txt $(docker run --rm -v csl-cb-data:/data "$ALPINE" sh -c 'test -f /data/in-volume.txt && echo 存在 || echo 不存在')"
docker volume rm csl-cb-data >/dev/null

# 6. PID 1 与 SIGTERM：docker stop -t 5 先发 SIGTERM，5 秒内没退出就发 SIGKILL
stop_time() {
  local name="$1"; shift
  docker run -d --name "$name" "$@" >/dev/null; sleep 2
  local s e; s=$(python3 -c 'import time; print(time.time())')
  docker stop -t 5 "$name" >/dev/null
  e=$(python3 -c 'import time; print(time.time())')
  printf '%.1f 秒，退出码 %s' "$(python3 -c "print($e - $s)")" "$(docker inspect -f '{{.State.ExitCode}}' "$name")"
  docker rm -f "$name" >/dev/null
}
out pid1.sleep "sleep 作为 PID 1：docker stop 耗时 $(stop_time csl-cb-pid1 "$ALPINE" sleep 300)"
out pid1.init "加 --init（tini 作为 PID 1）：docker stop 耗时 $(stop_time csl-cb-init --init "$ALPINE" sleep 300)"
out pid1.java "Java 作为 PID 1：docker stop 耗时 $(stop_time csl-cb-java -v "$PWD/src:/src:ro" "$J25" java /src/Sleeper.java)"

docker version --format 'docker_server: {{.Server.Version}}' >"$OUT/docker-version.txt"
write_environment "$OUT/environment.txt" "alpine: $ALPINE" "busybox: $BUSYBOX" "jdk_image: $J25"
cleanup
cat "$f" >&2
expect_regex "$f" "^ns\.pid	与宿主机不同" "pid namespace 独立"
expect_regex "$f" "^ns\.net	与宿主机不同" "net namespace 独立"
expect_regex "$f" "^ns\.mnt	与宿主机不同" "mnt namespace 独立"
expect_regex "$f" "^ns\.user	与宿主机相同" "默认不启用 user namespace"
expect_line "$f" "ns.uid	容器进程在宿主机上的 uid：0" "容器里的 root 就是宿主机上的 uid 0"
expect_regex "$f" "^ns\.pid_inside	容器里看到的进程：1:sleep " "容器里 sleep 是 PID 1"
expect_line "$f" "cgroup.files	memory.max=268435456 cpu.max=50000 100000" "限额写在 cgroup v2 的 memory.max 与 cpu.max 里"
expect_regex "$f" "^cgroup\.jvm	availableProcessors=1 maxHeapMB=1[12][0-9]$" "256MB、0.5 CPU：JVM 算出 1 个 CPU，小内存时最大堆取一半（MinRAMPercentage）"
expect_regex "$f" "^cgroup\.jvm_1g	availableProcessors=2 maxHeapMB=2[45][0-9]$" "1GB、2 CPU：最大堆取四分之一（MaxRAMPercentage）"
expect_line "$f" "oom	退出码 137，OOMKilled=true" "超过内存限额被 OOM 杀掉"
expect_regex "$f" "^layer\.after_rm	删除容器后新容器里：/tmp/in-layer\.txt 不存在，卷里的 in-volume\.txt 存在$" "可写层随容器删除，卷保留"
expect_regex "$f" "^pid1\.sleep	.*耗时 5\.[0-9] 秒，退出码 137$" "PID 1 不处理 SIGTERM：等满 5 秒后被 SIGKILL"
expect_regex "$f" "^pid1\.init	.*耗时 0\.[0-9] 秒，退出码 143$" "加 --init：立即退出"
expect_regex "$f" "^pid1\.java	.*耗时 [0-2]\.[0-9] 秒，退出码 143$" "Java 作为 PID 1：JVM 处理 SIGTERM 后退出"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
k = re.search(r"^kernel\t(.*)$", t, re.M)[1]
vals = re.findall(r"uname -r：([^；]+)", k)
assert len(set(vals)) == 1, vals
tools = re.search(r"nproc=(\d+) /proc/meminfo MemTotal=(\d+)MB", t)
assert int(tools[1]) > 1 and int(tools[2]) > 1024, tools.groups()        # 工具看到的是整台机器
print(f"通过：三个镜像的内核相同（{vals[0]}）；nproc={tools[1]}、MemTotal={tools[2]}MB，没有反映限额")
PY
log "全部通过，输出在 $OUT"
