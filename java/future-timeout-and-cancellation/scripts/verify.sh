#!/usr/bin/env bash
# 超时与取消之后任务是否停下：Future.get 超时、cancel、CompletableFuture、anyOf、请求登记表、shutdown/shutdownNow、invokeAll、Socket 读上的中断、结构化并发
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java --enable-preview --source 25 src/CancelLab.java 2>"$OUT/stderr.txt" >"$f"
[ -s "$OUT/stderr.txt" ] || rm -f "$OUT/stderr.txt"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "flags: --enable-preview（仅结构化并发一节需要）"
cat "$f" >&2
expect_line "$f" "env	java.version=25.0.4" "运行在 JDK 25.0.4"
expect_line "$f" "get_timeout	get(100ms) 抛出 TimeoutException；isDone=false，isCancelled=false；任务仍在运行" "get 超时不取消任务"
expect_regex "$f" "^cancel\.responds\.false	.*isCancelled=true，get 得到 CancellationException；任务仍在运行$" "cancel(false) 不影响已经在运行的任务"
expect_regex "$f" "^cancel\.responds\.true	.*任务已停止（被中断后退出）$" "cancel(true) 能停下响应中断的任务"
expect_regex "$f" "^cancel\.swallows\.true	.*isCancelled=true，.*任务仍在运行$" "吞掉中断的任务：Future 已取消，任务照跑"
expect_regex "$f" "^cancel\.busy\.true	.*isCancelled=true，.*任务仍在运行$" "不检查中断的任务：Future 已取消，任务照跑"
expect_regex "$f" "^cf\.cancel	CompletableFuture\.cancel\(true\) 返回 true；isCancelled=true，.*任务仍在运行$" "CompletableFuture.cancel 不中断执行任务的线程"
expect_line "$f" "cf.orTimeout	orTimeout(100ms) 之后 get 得到 ExecutionException: TimeoutException；任务仍在运行" "orTimeout 只让结果超时"
expect_line "$f" "anyOf.loser	anyOf 返回 fast；落选的任务：任务仍在运行" "anyOf 不取消落选的任务"
expect_regex "$f" "^anyOf\.failure_first	.*anyOf 的结果是 ExecutionException: IllegalStateException；" "anyOf 取的是第一个完成的，不是第一个成功的"
expect_regex "$f" "^registry\.get_timeout	.*登记表里还剩 1 项；迟到的响应 complete 返回 true" "只在调用方超时：登记项泄漏"
expect_regex "$f" "^registry\.or_timeout	.*登记表里还剩 0 项；迟到的响应找不到登记项，被丢弃；对已超时的 Future 再 complete 返回 false" "终态只有一个赢家，登记项被清理"
expect_regex "$f" "^shutdown	shutdown\(\) 用时 [0-9]ms 返回；awaitTermination\(200ms\)=false；运行中的任务：任务仍在运行" "shutdown 不等待也不中断"
expect_regex "$f" "^shutdownNow	shutdownNow\(\) 返回 1 个未开始的任务；运行中的任务：任务已停止（被中断后退出）；.*排队任务的 Future：get 超时（未完成），isDone=false$" "shutdownNow 返回的任务，其 Future 永远不会完成"
expect_line "$f" "shutdownNow.cancel_returned	对返回的任务逐个 cancel 之后，排队任务的 Future：CancellationException，isDone=true" "需要自己取消返回的任务"
expect_line "$f" "discard	线程 1、队列 1 都占满后再提交，DiscardPolicy 丢弃的任务：get 超时（未完成），isDone=false，isCancelled=false" "DiscardPolicy 丢弃的任务，其 Future 永远不会完成"
expect_regex "$f" "^invokeAll	.*响应中断的任务 isCancelled=true，任务已停止（被中断后退出）；不检查中断的任务 isCancelled=true，任务仍在运行$" "invokeAll 超时会取消，但同样依赖任务响应中断"
expect_regex "$f" "^socket\.platform	.*仍阻塞在 read，线程存活=true$" "平台线程阻塞在 Socket 读：中断无效"
expect_regex "$f" "^socket\.virtual	.*read 抛出 SocketException（Closed by interrupt），线程存活=false$" "虚拟线程阻塞在 Socket 读：中断会关闭 Socket"
expect_regex "$f" "^scope\.responds	join 抛出 FailedException（IllegalStateException）；.*任务已停止（被中断后退出）$" "结构化并发：一个子任务失败，兄弟任务被中断"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
ms = {k: int(v) for k, v in re.findall(r"^scope\.(\w+)\t.*用时 (\d+)ms", t, re.M)}
assert ms["responds"] < 400, ms                      # 兄弟任务响应中断：很快离开作用域
assert ms["busy"] >= 900, ms                         # 兄弟任务不检查中断：作用域一直等到它自己跑完
assert "scope.busy" in t and re.search(r"^scope\.busy\t.*任务已停止（跑完全程）$", t, re.M)
print(f"通过：作用域关闭等待子任务结束（响应中断 {ms['responds']}ms，不检查中断 {ms['busy']}ms）")
PY
log "全部通过，输出在 $OUT"
