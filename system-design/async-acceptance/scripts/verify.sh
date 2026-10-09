#!/usr/bin/env bash
# 受理不等于完成：202 之后任务失败、提交超时后的重复任务与幂等键、轮询的终态集合、重启后的任务表与租约、状态只能向前、旧执行者的迟到上报
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC  src/AcceptLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
accepted.response	POST 返回 202，Location: /jobs/job-1，响应体 {"id":"job-1","state":"QUEUED"}
accepted.final	后台执行完之后 GET：200 {"id":"job-1","state":"FAILED","attempt":1,"error":"source table missing"}
retry.no_key	第一次提交：客户端超时；重试返回 202 {"id":"job-2","state":"QUEUED"}；服务端登记的任务数 2
retry.with_key	带同一个 Idempotency-Key：第一次提交：客户端超时；重试返回 202 {"id":"job-1","state":"QUEUED"}；服务端登记的任务数 1
poll.terminal_set	任务还在排队时查询到 QUEUED：「不是 RUNNING 就算结束」的判断 = true，「属于终态集合才算结束」的判断 = false
restart.in_memory	任务表在内存里，重启后 GET job-1：404 {"error":"no such job"}
restart.durable_no_lease	任务表持久化、没有租约：重启并经过 100 个时间单位后 GET job-1：200 {"id":"job-1","state":"RUNNING","attempt":1}；后台再取任务得到 null
restart.durable_lease	任务表持久化、执行中的任务带租约：重启并经过 100 个时间单位后 GET job-1：200 {"id":"job-1","state":"QUEUED","attempt":1}；后台再取任务得到 job-1，执行完后 200 {"id":"job-1","state":"SUCCEEDED","attempt":2}
transition.unguarded	任务成功之后又到了一条迟到的进度上报：写入 = true，GET：200 {"id":"job-1","state":"RUNNING","attempt":1}
transition.guarded	终态之后拒绝回退：迟到的进度上报写入 = false，重复的完成上报写入 = false，GET：200 {"id":"job-1","state":"SUCCEEDED","attempt":1}
stale_worker	租约过期后任务被第二个执行者接手（attempt 2），旧执行者此时上报完成：写入 = true，GET：200 {"id":"job-1","state":"SUCCEEDED","attempt":2}
stale_worker.fenced	完成上报带上执行序号：旧执行者（attempt 1）上报写入 = false，GET：200 {"id":"job-1","state":"RUNNING","attempt":2}；当前执行者（attempt 2）上报写入 = true，GET：200 {"id":"job-1","state":"SUCCEEDED","attempt":2}
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
