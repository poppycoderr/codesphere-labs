#!/usr/bin/env bash
# 进程内观察者的订阅、退订、once、异常隔离、重入与事件表（Node 24.21.0）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 node:24.21.0-slim 容器；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="node:24.21.0-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$IMG" node src/run.mjs 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
dup	同一个函数订阅两次后通知一次：朴素发布器调用 2 次，改进版（订阅时去重）1 次，Node EventEmitter 2 次
unsubscribe-during-emit	a 在被通知时退订自己：朴素发布器通知了 [a,c]（b 被跳过），快照通知 [a,b,c]，Node EventEmitter [a,b,c]
once-reentrant	once 的回调里再次触发同一事件：先调用后摘除的朴素 once 执行了 5 次，先摘除后调用 1 次，Node EventEmitter 1 次
throw.naive	第二个监听器抛错：朴素发布器执行了 [记账]，抛出「短信服务超时」（积分没有执行）
throw.isolated	逐个隔离并汇总：执行了 [记账,积分]，抛出 AggregateError（1 个监听器失败）
throw.node-emitter	Node EventEmitter：执行了 [记账]，抛出「短信服务超时」
throw.event-target	EventTarget：执行了 [记账,积分]，dispatchEvent 正常返回，错误作为未捕获异常报告 1 次（短信服务超时）
reentrant-order	第一个监听器在收到 2 时把值改成 3：第二个监听器收到的顺序是 [3,2]，最后停在 2，而当前值是 3
prototype-keys	普通对象做事件表时 emit('toString')：TypeError：(this.handlers[e] || []).forEach is not a function；策略表按标签查 'constructor'：得到 100（没有走「未知类型」分支）；改用 Map：未知类型 constructor
error-event	Node EventEmitter 没有 error 监听器时 emit('error')：抛出「连接断开」
node	v24.21.0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
