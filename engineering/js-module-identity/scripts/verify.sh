#!/usr/bin/env bash
# CommonJS 与 ESM 的循环依赖、实时绑定、模块缓存的身份与双包风险（Node 24.21.0）
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
cjs.cycle	pricing 加载时看到的 order 导出：[]；pricing 加载时算出的默认税额：NaN；运行时 total(100)=112.99999999999999
esm.cycle	pricing 顶层调用 order 的函数声明 rate()=0.13；ReferenceError: Cannot access 'TAX' before initialization
binding	ESM 导入 count 后调用两次 inc()：读到 2（实时绑定）；CommonJS 解构出来的 count 与 通过模块对象读的 count：0 / 2
dup.package	应用看到的注册数 0（插件依赖的 registry@1.4.0 被装在插件自己的 node_modules 里）
dual.package	ESM 一侧设为 prod 后，CommonJS 一侧读到 default；两边是同一个对象=false
esm.cache-key	同一路径两次导入是同一个实例=true；加上 ?v=2 后是同一个实例=false
node	v24.21.0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
