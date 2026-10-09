#!/usr/bin/env bash
# 并行流与公共线程池：阻塞调用放进并行流、多个请求同时使用、不带执行器的 supplyAsync、自建池、ThreadLocal、类初始化死锁，以及 JDK 8/21/25 在小核数下的差别
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 8、21、25 容器；约 1 分钟。结论行是确定的，原始耗时另存，不参与比较
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J8="eclipse-temurin@sha256:3a280002b0d1212a62e9f5095c10b07ef3b62c1b13916fe093438e7e9af44b8b"
J21="eclipse-temurin@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d"
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"; v="$OUT/async-thread-by-jdk.tsv"; t="$OUT/timings.log"
# 主实验：固定按 4 个 CPU 计算公共池大小（并行度 3）
docker run --rm -v "$PWD:/w" -w /w "$J25" java -XX:ActiveProcessorCount=4 src/ParallelLab.java 2>"$OUT/stderr.log" >"$f"
grep '^timing' "$OUT/stderr.log" >"$t" || true
# 同一个 Java 8 语法的小程序，在三个版本、三种 CPU 数下各跑一次
: >"$v"
for img in "$J8" "$J21" "$J25"; do
  docker run --rm -v "$PWD/src:/src:ro" "$img" sh -c 'mkdir -p /tmp/c && javac -d /tmp/c /src/AsyncThread.java 2>/dev/null && for n in 1 2 4; do java -XX:ActiveProcessorCount=$n -cp /tmp/c AsyncThread; done' >>"$v" 2>>"$OUT/stderr.log"
done
grep '^timing' "$OUT/stderr.log" >"$t" || true
rm -f "$OUT/stderr.log"
write_environment "$OUT/environment.txt" "jdk8_image: $J8" "jdk21_image: $J21" "jdk25_image: $J25"
cat "$f" "$v" >&2
diff - "$f" <<'EXPECTED' || fail "主实验的结论行与预期不一致（原始耗时见 $t）"
env	java.version=25.0.4.1
pool	availableProcessors = 4，公共池并行度 = 3
single	8 次 200 ms 的阻塞调用放进并行流：参与执行的有 [公共池线程, 调用线程]；耗时在 380–650 ms 之间 = true
concurrent.alone	单独一个请求（4 次调用）：耗时在 180–350 ms 之间 = true
concurrent.eight	8 个请求同时进来：超过 550 ms 的不少于 4 个 = true，最慢的在 750–1100 ms 之间 = true（4 次调用完全串行是 800 ms）
async.thread	supplyAsync 不传执行器：任务运行在公共池线程
async.default	12 个 200 ms 的阻塞任务用默认执行器：耗时在 780–1100 ms 之间 = true
async.tiny	这期间提交的一个空任务：等了 600 ms 以上才开始执行 = true
async.stream	这期间的一个纯计算并行流（1000 个元素求和 = 499500）：参与执行的只有 [调用线程]，耗时不到 100 ms = true
async.dedicated	同样 12 个任务交给 12 线程的专用线程池：耗时在 180–350 ms 之间 = true
own_pool	在 new ForkJoinPool(8) 里提交同一个并行流：参与执行的有 [自建 ForkJoinPool 的线程]；耗时在 180–350 ms 之间 = true
thread_local	调用线程设置了 ThreadLocal 之后跑并行流：[公共池线程读到 null, 调用线程读到 tenant-a]
shared_list	并行流里 forEach(list::add) 往 ArrayList 里放 10 万个元素：丢元素、出现 null 或抛异常 = true；collect(toList()) 得到 100000 个
cpus.1	-XX:ActiveProcessorCount=1：公共池并行度 1；8 次阻塞调用的并行流用了 2 个线程，约 4 轮；supplyAsync 的任务运行在公共池线程
cpus.2	-XX:ActiveProcessorCount=2：公共池并行度 1；8 次阻塞调用的并行流用了 2 个线程，约 4 轮；supplyAsync 的任务运行在公共池线程
cpus.4	-XX:ActiveProcessorCount=4：公共池并行度 3；8 次阻塞调用的并行流用了 4 个线程，约 2 轮；supplyAsync 的任务运行在公共池线程
cpus.8	-XX:ActiveProcessorCount=8：公共池并行度 7；8 次阻塞调用的并行流用了 8 个线程，约 1 轮；supplyAsync 的任务运行在公共池线程
clinit	静态初始化块里跑并行流（lambda 调用了本类的静态方法）：5 秒内没有结束，被强制终止
EXPECTED
diff - "$v" <<'EXPECTED_JDK' || fail "各版本的 supplyAsync 行为与预期不一致"
java 1.8.0_504，availableProcessors = 1，公共池并行度 1：supplyAsync 运行在每个任务新建一个线程（两次的线程名 Thread-0、Thread-1）；6 个 200 ms 的阻塞任务约 1 轮跑完
java 1.8.0_504，availableProcessors = 2，公共池并行度 1：supplyAsync 运行在每个任务新建一个线程（两次的线程名 Thread-0、Thread-1）；6 个 200 ms 的阻塞任务约 1 轮跑完
java 1.8.0_504，availableProcessors = 4，公共池并行度 3：supplyAsync 运行在公共池线程；6 个 200 ms 的阻塞任务约 2 轮跑完
java 21.0.12，availableProcessors = 1，公共池并行度 1：supplyAsync 运行在每个任务新建一个线程（两次的线程名 Thread-0、Thread-1）；6 个 200 ms 的阻塞任务约 1 轮跑完
java 21.0.12，availableProcessors = 2，公共池并行度 1：supplyAsync 运行在每个任务新建一个线程（两次的线程名 Thread-0、Thread-1）；6 个 200 ms 的阻塞任务约 1 轮跑完
java 21.0.12，availableProcessors = 4，公共池并行度 3：supplyAsync 运行在公共池线程；6 个 200 ms 的阻塞任务约 2 轮跑完
java 25.0.4.1，availableProcessors = 1，公共池并行度 1：supplyAsync 运行在公共池线程；6 个 200 ms 的阻塞任务约 6 轮跑完
java 25.0.4.1，availableProcessors = 2，公共池并行度 1：supplyAsync 运行在公共池线程；6 个 200 ms 的阻塞任务约 6 轮跑完
java 25.0.4.1，availableProcessors = 4，公共池并行度 3：supplyAsync 运行在公共池线程；6 个 200 ms 的阻塞任务约 2 轮跑完
EXPECTED_JDK
log "全部通过：结论行与预期逐行一致，原始耗时在 $t"
