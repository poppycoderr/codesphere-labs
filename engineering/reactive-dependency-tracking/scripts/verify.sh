#!/usr/bin/env bash
# 依赖的动态收集与清理、嵌套 effect、自我触发、批量调度；@vue/reactivity 3.5.44 与 React useSyncExternalStore 对照（Node 24.21.0）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 node:24.21.0-slim 容器，依赖由 package-lock.json 锁定；约 20 秒（首次需要下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="node:24.21.0-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
f="$OUT/output.tsv"
mkdir -p "$LABS_CACHE/npm"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/npm:/root/.npm" -w /w "$IMG" sh -c 'npm ci --no-audit --no-fund --loglevel=error >/dev/null 2>&1 && node src/run.mjs' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
branch.naive	不清理旧依赖的最小实现：登出后 user 已不再被读取，user 又改了两次，effect 额外重跑 2 次
branch.cleanup	每次运行前清理依赖：登出后 user 已不再被读取，user 又改了两次，effect 额外重跑 0 次
branch.vue	@vue/reactivity 3.5.44：登出后 user 已不再被读取，user 又改了两次，effect 额外重跑 0 次
nested.naive	不用栈恢复当前观察者：外层 effect 在创建内层 effect 之后读取 count，count 变化后外层重跑 0 次
nested.stack	用栈恢复：外层 effect 在创建内层 effect 之后读取 count，count 变化后外层重跑 1 次
nested.vue	@vue/reactivity：外层 effect 在创建内层 effect 之后读取 count，count 变化后外层重跑 1 次
self-trigger	effect 里执行 n = n + 1：不排除当前正在运行的 effect 的最小实现得到 RangeError；@vue/reactivity 得到 n=1（不会自己触发自己）
batch	同一轮里改三个字段：同步 effect 重跑 3 次；scheduler 把任务放进按任务去重的微任务队列后重跑 1 次
batch.stuck	调度器在刷新队列时没有用 finally 复位：一个任务抛错后，之后排进来的任务执行了 0 次（flushing 一直是 true）
uses.snapshot	getSnapshot 每次返回新对象：Error：Maximum update depth exceeded. This can happen when a compon；返回同一个对象：渲染成功，显示 1
uses.warning	React 给出的提示：The result of getSnapshot should be cached to avoid an infinite loop
vue-reactivity	3.5.44
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
