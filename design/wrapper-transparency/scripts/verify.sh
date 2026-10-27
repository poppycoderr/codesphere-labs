#!/usr/bin/env bash
# 包装函数与 Proxy 的透传契约；TypeScript 两套装饰器语义（Node 24.21.0 + TypeScript 7.0.2）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 node:24.21.0-slim 容器，TypeScript 由 package-lock.json 锁定；约 20 秒（首次需要下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="node:24.21.0-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
f="$OUT/output.tsv"
mkdir -p "$LABS_CACHE/npm"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/npm:/root/.npm" -w /w "$IMG" sh src/run.sh 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
this	箭头函数包装直接调用原函数：TypeError：Cannot read properties of undefined (reading 'balance')；用 fn.apply(this, args)：余额 70
return	包装里调用了原函数但没有 return：调用方拿到 undefined（余额其实已是 70）
throw	包装捕获异常只记日志：余额 10 取 30，调用方拿到 undefined，以为成功了
async	异步方法被拒绝：同步写法的包装记录了「耗时记录在 Promise 完成之前（约 0ms）」，没有记到失败；await 之后的包装记录了「失败 余额不足」
order	compose(鉴权, 重试, 计时)：鉴权 进入 → 重试 进入 → 计时 进入 → 业务 → 计时 退出 → 重试 退出 → 鉴权 退出（写在最前面的包在最外层）
proxy.indexof	用 indexOf(key) 当布尔值判断是否隐藏：secret（下标 0）读到 undefined，token（下标 1）读到 t-1，name 读到 alice
proxy.no-return	允许分支里调用了 Reflect.get 却没有 return：name 读到 undefined
proxy.receiver	访问器 display 里读 this.secret：转发时用 t[k] 得到「alice（有密钥）」（this 是原对象，绕过了隐藏）；用 Reflect.get(t, k, receiver) 得到「alice（无密钥）」
proxy.set	set 陷阱写入后没有返回 true：严格模式下赋值 TypeError（值其实已经写进去了：1）
proxy.invariant	对不可写、不可配置的属性 version=1，get 陷阱返回 2：TypeError
memo	键用 args.join(',')：先查 ('a,b') 再查 ('a','b') 得到「1 段：a,b」（命中了前者的结果），1 与 '1' 共用一个键；键用 JSON.stringify：('a','b') 得到「2 段：a|b」
node	v24.21.0
tsc	Version 7.0.2
decorator.standard	调用 withdraw(30)
decorator.standard	结果 70
decorator.legacy	调用 withdraw(30)
decorator.legacy	结果 70
decorator.mixed	旧签名的装饰器不加 --experimentalDecorators 编译：error TS1241 error TS1270
decorator.mixed-run	照样产出的 JS 运行时 TypeError: Cannot read properties of undefined (reading 'value'
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
