#!/usr/bin/env bash
# structuredClone、JSON 往返与手写递归深拷贝的差别，领域对象的复制（Node 24.21.0）
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
clone.keeps	structuredClone：Date 仍是 Date=true，Map 仍是 Map=true，NaN 保留=true，undefined 字段保留=true，环保留=true，两处共享的对象仍是同一个=true
clone.drops	structuredClone：访问器 total 变成普通数据属性=true，Symbol 键还在=false
clone.function	对象里有函数时：DOMException DataCloneError：() => '' could not be cloned.
clone.class	复制一个 Money 实例：得到的是 Money=false，cents=undefined，调用 plus：TypeError：m.plus is not a function
json.loses	JSON 往返：note 字段还在=false，createdAt 是 string，items 变成 {}，NaN 变成 null，共享对象还是同一个=false
json.throws	有环：TypeError：Converting circular structure to JSON；有 BigInt：TypeError：Do not know how to serialize a BigInt
naive.cycle	手写递归遇到环：RangeError：Maximum call stack size exceeded
naive.loses	手写递归：createdAt 变成 {}，items 变成 {}，共享对象被复制成两份=true
naive.own-key	数据里有一个叫 hasOwnProperty 的字段：TypeError：o.hasOwnProperty is not a function
naive.proto	复制 JSON.parse 出来的 {"__proto__": {"isAdmin": true}}：副本自身有 isAdmin=false，读 copied.isAdmin 得到 true（赋值 copy['__proto__'] 改掉了副本的原型）
domain	展开 {...money}：私有字段丢了，cents=undefined；领域对象应提供自己的复制或用构造器重建：new Money(m.cents, m.currency).cents=1999
node	v24.21.0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
