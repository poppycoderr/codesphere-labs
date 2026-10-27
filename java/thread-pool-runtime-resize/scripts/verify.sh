#!/usr/bin/env bash
# core/max 先后约束、缩容与在途任务、afterExecute 与 submit、钩子异常、CallerRunsPolicy（JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$IMG" java -Duser.timezone=UTC src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
resize.up.core-first	从 core 2 / max 4 扩到 8 / 16，先改 core：setCorePoolSize(8) IllegalArgumentException，再改 max 成功，结果 core 2 / max 16
resize.up.max-first	先改 max 再改 core：结果 core 8 / max 16
resize.down.max-first	从 8 / 16 缩到 2 / 4，先改 max：setMaximumPoolSize(4) IllegalArgumentException，配置仍是 core 8 / max 16
resize.down.core-first	先改 core 再改 max：结果 core 2 / max 4
shrink.inflight	4 个线程都在执行、队列里还有 6 个任务时缩到 1 / 1：调整后立刻看线程数 4 → 4（正在执行的线程不会被打断）；全部结束后完成 10 个任务（排队的任务没有丢）
hook.after-execute	afterExecute 拿到的 Throwable：execute 提交的任务 IllegalStateException，submit 提交的任务 null（异常在 Future 里：submit 的任务失败）
hook.throws	单线程池里第一次 afterExecute 抛错：3 个任务由 2 个不同的工作线程执行（抛错的线程退出并被替换），完成的任务数 3
caller-runs	线程与队列都满时第 3 个任务由 [main] 执行（调用线程），3 个任务里 beforeExecute 只被调用了 2 次
config.applied	配置中心下发 core 2 / max 4，按「先 max 后 core」直接应用：未生效（IllegalArgumentException），池的实际配置 core 8 / max 16（期望值与实际值要分别记录）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
