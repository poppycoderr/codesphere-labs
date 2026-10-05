#!/usr/bin/env bash
# 压测标记的传播：线程池、CompletableFuture、定时任务、InheritableThreadLocal、消息、缓存；包装任务、ScopedValue 与严格模式
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java --enable-preview --source 25 src/MarkerLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 场景顺序执行、每个场景单独建线程池，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4
same_thread	压测请求在入口线程里直接写订单：影子表
thread_pool	压测请求把写订单交给线程池：工作线程看到 无标记，订单写进 正式表
completable_future	supplyAsync 里写订单：看到 无标记，订单写进 正式表
scheduled_retry	压测请求失败后 20 毫秒重试：订单写进 正式表
inheritable.shadow_first	压测请求先到，工作线程由它创建：压测订单写进 影子表；随后的正常订单写进 影子表
inheritable.normal_first	正常请求先到，工作线程由它创建：正常订单写进 正式表；随后的压测订单写进 正式表
message	压测请求发出两条消息：不带头的那条，消费者把订单写进 正式表；带 x-traffic 头的那条写进 影子表
cache	共用键：正常请求读到价格 1；压测流量的键加前缀：正常请求读到价格 9900
wrapped	提交时捕获、执行后清理：压测订单写进 影子表，随后的正常订单写进 正式表，工作线程上残留的标记是 无标记
scoped_value	范围内写订单：影子表；范围内提交给线程池的任务看到 无标记；StructuredTaskScope 分叉出的子任务看到 SHADOW；范围结束后同一线程看到 无标记
strict	标记在线程池里丢失时：抛出 IllegalStateException（traffic context missing），订单 没有写入；入口明确标为 NORMAL 的请求：订单写进 正式表
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
