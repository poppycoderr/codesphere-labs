#!/usr/bin/env bash
# 容器内存记账：申请与触碰、匿名页与文件页、tmpfs、上限之下的回收与 OOM、-Xms 与 AlwaysPreTouch
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14 与 temurin 25 容器，一个一次性数据卷（约 800 MB，结束时删除）；约 1 分钟
# 只在实验自己创建的容器与数据卷里读写，不触碰宿主机上的其他容器
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
VOL=labs-cma-data
cleanup() { docker rm -f labs-cma-run >/dev/null 2>&1 || true; docker volume rm "$VOL" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker volume create "$VOL" >/dev/null

# run <上限> <场景> <输出文件>：在带内存上限、不允许换出的容器里运行一个场景，追加退出码与 OOMKilled
run() {
  docker rm -f labs-cma-run >/dev/null 2>&1 || true
  docker run --name labs-cma-run --memory "$1" --memory-swap "$1" --shm-size 512m -v "$PWD/src:/src:ro" -v "$VOL:/data" "$PY" python /src/memlab.py "$2" >"$3" 2>&1 || true
  docker inspect labs-cma-run --format "$2.container	exit={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}}" >>"$3"
  docker rm labs-cma-run >/dev/null
}
run 1g accounting "$OUT/accounting.tsv"
run 300m under_limit "$OUT/under-limit.tsv"
run 300m over_limit "$OUT/over-limit.tsv"
run 300m shm_over_limit "$OUT/shm-over-limit.tsv"
{
  docker run --rm --memory 2g --memory-swap 2g -v "$PWD/src:/src:ro" "$J25" java -Xms1g -Xmx1g /src/Rss.java "jvm.xms_1g"
  docker run --rm --memory 2g --memory-swap 2g -v "$PWD/src:/src:ro" "$J25" java -Xms1g -Xmx1g -XX:+AlwaysPreTouch /src/Rss.java "jvm.xms_1g_pretouch"
} >"$OUT/jvm.tsv"
write_environment "$OUT/environment.txt" "python_image: $PY" "jdk25_image: $J25" "container_kernel: $(docker run --rm "$PY" uname -r)"
cat "$OUT"/accounting.tsv "$OUT"/under-limit.tsv "$OUT"/over-limit.tsv "$OUT"/shm-over-limit.tsv "$OUT"/jvm.tsv >&2

a="$OUT/accounting.tsv"
# num <文件> <键> <列前缀>：取出某一行里以该前缀开头的那一列的数值
num() { awk -F'\t' -v k="$2" -v c="$3" '$1==k { for (i=2;i<=NF;i++) if (index($i,c)==1) { v=$i; sub(c,"",v); gsub(/[^0-9+-]/,"",v); print v+0 } }' "$1"; }
between() { awk -v v="$1" -v lo="$2" -v hi="$3" 'BEGIN{exit !(v>=lo && v<=hi)}' || fail "$4：$1 不在 [$2, $3]"; log "通过：$4（$1）"; }

between "$(num "$a" anon.map_256mb "VmSize ")" 256 256 "映射 256 MB：VmSize 增加 256 MB"
between "$(num "$a" anon.map_256mb "VmRSS ")" 0 1 "映射 256 MB：VmRSS 不变"
between "$(num "$a" anon.read_every_page "minflt ")" 65536 66000 "逐页读：约 65536 次次缺页"
between "$(num "$a" anon.read_every_page "cgroup anon ")" 0 1 "逐页读：cgroup anon 不变"
between "$(num "$a" anon.write_every_page "minflt ")" 65536 66000 "逐页写：约 65536 次次缺页"
between "$(num "$a" anon.write_every_page "cgroup anon ")" 255 257 "逐页写：cgroup anon 增加 256 MB"
between "$(num "$a" anon.write_again "minflt ")" 0 50 "再写一遍：没有新的缺页"
between "$(num "$a" anon.write_every_page_thp "minflt ")" 100 2000 "透明大页：缺页次数比页数少两个数量级"
between "$(num "$a" file.write_200mb "cgroup file ")" 195 205 "写 200 MB 文件：cgroup file 增加约 200 MB"
between "$(num "$a" file.write_200mb "VmRSS ")" -1 2 "写 200 MB 文件：进程 VmRSS 不变"
between "$(num "$a" file.drop_cache "cgroup file ")" -205 -195 "丢弃页缓存：cgroup file 减少约 200 MB"
between "$(num "$a" file.mmap_touch_cold "RssFile ")" 195 205 "mmap 后逐页读：RssFile 增加约 200 MB"
between "$(num "$a" file.mmap_touch_cold "majflt ")" 1 2000 "冷缓存顺序访问：有主缺页但远少于页数（预读）"
between "$(num "$a" file.mmap_touch_warm "majflt ")" 0 0 "缓存已在内存：没有主缺页"
between "$(num "$a" file.mmap_touch_cold_random_800_pages "majflt ")" 780 800 "冷缓存、关闭预读、碰 800 页：约 800 次主缺页"
between "$(num "$a" shm.write_200mb "其中 shmem ")" 195 205 "向 /dev/shm 写 200 MB：shmem 增加约 200 MB"
between "$(num "$a" shm.drop_cache "cgroup file ")" 0 0 "对 tmpfs 文件丢弃缓存：没有效果"
expect_regex "$OUT/under-limit.tsv" "limit.after_read_600mb.*oom_kill=0" "上限 300 MB 读 600 MB 文件：没有 OOM"
between "$(num "$OUT/under-limit.tsv" limit.after_read_600mb "memory.current ")" 200 300 "读完后 memory.current 贴近上限"
between "$(num "$OUT/under-limit.tsv" limit.after_anon_200mb "anon ")" 195 210 "随后申请 200 MB 匿名内存成功"
between "$(num "$OUT/under-limit.tsv" limit.after_anon_200mb "file ")" 0 110 "文件页被回收让出空间"
expect_line "$OUT/under-limit.tsv" "under_limit.container	exit=0 OOMKilled=false" "该容器正常退出"
expect_line "$OUT/over-limit.tsv" "over_limit.container	exit=137 OOMKilled=true" "上限 300 MB 写 400 MB 匿名内存：被 OOM 杀死"
if grep -q survived "$OUT/over-limit.tsv" "$OUT/shm-over-limit.tsv"; then fail "超限场景不应运行到结束"; fi
expect_line "$OUT/shm-over-limit.tsv" "shm_over_limit.container	exit=137 OOMKilled=true" "上限 300 MB 向 tmpfs 写 400 MB：被 OOM 杀死"
between "$(num "$OUT/jvm.tsv" jvm.xms_1g "VmRSS ")" 20 400 "-Xms1g：常驻内存远小于 1 GB"
between "$(num "$OUT/jvm.tsv" jvm.xms_1g_pretouch "VmRSS ")" 1024 1500 "-Xms1g 加 AlwaysPreTouch：常驻内存超过 1 GB"
log "全部通过，输出在 $OUT"
