#!/usr/bin/env bash
# 流程骨架（校验、幂等、执行、清理）中的上下文所有权与失败语义（JDK 25）
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
nest.naive	外层处理订单 A 时调用了处理订单 B 的流程，内层结束后外层再读上下文：null
nest.restore	进入时保存、退出时恢复：内层结束后外层读到 t1/A
nest.explicit	上下文作为参数显式传递：内层结束后外层仍持有 t1/A
reuse.leak	同一线程上的下一个任务（没有设置上下文）读到：tenant-x/X1
reuse.finally	清理放进 finally 后，下一个任务读到：null
async.threadlocal	在流程里 supplyAsync 到线程池，任务里读 ThreadLocal：null
async.explicit	把上下文作为值捕获进任务：t2/C
idem.check-then-act	两个线程同时处理命令 cmd-1，先查是否处理过再执行：执行了 2 次
idem.claim	用一次原子的「占位」代替先查后写（putIfAbsent，相当于唯一约束）：执行了 1 次
idem.random-id	同一个扣款命令因超时重试一次，每次尝试生成新的随机事件 ID：扣款 2 次
idem.command-key	用调用方提供的命令键 pay:order-42 去重：扣款 1 次
hook.after-returning	收尾只写在正常返回之后，执行抛错时任务状态停在：处理中
hook.finally	每条退出路径都写终态：任务状态为 失败（下游超时）
bag.typo	上下文是 Map<String, Object>，写入键 tenantId、读取时写成 tenantID：读到 null，没有任何报错
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
