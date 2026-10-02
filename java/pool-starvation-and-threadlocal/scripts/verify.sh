#!/usr/bin/env bash
# 线程池上的两个隐蔽问题：父任务在同一个池里等子任务，复用线程上残留的 ThreadLocal
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/PoolLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
expect_line "$f" "env	java.version=25.0.4" "运行在 JDK 25.0.4"
expect_line "$f" "starvation.same_pool	500ms 内完成的父任务 0/2；池内活跃线程 2，队列里等待的任务 2" "同一个池：两个线程都在等排在队列里的子任务"
expect_line "$f" "starvation.child_pool	500ms 内完成的父任务 2/2" "子任务用单独的池：正常完成"
expect_line "$f" "starvation.virtual	500ms 内完成的父任务 2/2" "每任务一个虚拟线程：正常完成"
expect_line "$f" "threadlocal.leak	任务 A set(\"alice\") 后不清理，同一线程上的任务 B 读到：alice" "ThreadLocal 残留到下一个任务"
expect_line "$f" "threadlocal.remove	任务在 finally 里 remove() 后，下一个任务读到：null" "finally 里 remove 后不残留"
expect_line "$f" "scopedvalue	ScopedValue.where(USER, \"carol\").run(…) 结束后，下一个任务里 USER.isBound()=false" "ScopedValue 的绑定随作用域结束"
log "全部通过，输出在 $OUT"
