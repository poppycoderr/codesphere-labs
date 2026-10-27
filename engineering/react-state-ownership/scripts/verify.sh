#!/usr/bin/env bash
# 渲染快照、函数式更新、浅复制与 memo、Object.is、key 与组件身份（React 19.3.1 + Testing Library + jsdom）
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
snapshot	同一次点击里连续三次 setCount(count + 1)：显示 1，handler 里 set 之后读到的 count 是 0；再用三次 setCount(c => c + 1)：显示 4
nested	push 进原数组再 setCart({...cart})：外层组件显示 2 件，memo 子组件仍显示 1 件；连同数组一起复制后子组件显示 3 件
object-is	修改对象的字段后 setP(同一个对象)：重新渲染 0 次，界面仍显示 alice
key.index	用下标做 key：给张三写了备注后删掉张三，李四那一行的输入框里是「张三的备注」
key.id	用 id 做 key：给张三写了备注后删掉张三，李四那一行的输入框里是「」
key.reset	切换到用户 u2：同一位置、同一组件类型时草稿保留为「写给 u1 的草稿」；以 userId 作为 key 时草稿是「」
falsy	数量为 0：value={qty || ''} 显示「」，value={qty ?? ''} 显示「0」
react	19.3.1
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
