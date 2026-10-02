#!/usr/bin/env bash
# 信号量的许可与资源的身份：同一对象放两次、没拿到许可也归还、泄漏、重复归还、归还后继续使用、损坏的资源；带租约的池
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/LeaseLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 场景全部是单线程顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4
permits.same_instance	2 个许可都借出：两个借用者拿到的是同一个对象（id 1 与 1）
permits.release_without_acquire	上限 2，一次 tryAcquire 超时（返回 false）后仍在 finally 里 release：全部归还后可用许可 3
permits.leak	两次借出后都因异常没有归还：可用许可 0，再借一次 tryAcquire(50ms) 返回 false
return.twice	id 1 被归还两次后池里有 3 项；依次借出 id 2、1、1：其中两个借用者同时持有 id 1 = true
return.use_after	归还后原借用者手里的引用与新借用者拿到的是同一个对象 = true：两方都能调用它
return.broken	损坏的资源原样归还：下一个借用者拿到 id 1，broken=true
lease.distinct	池大小 2，两个租约拿到 id 1 与 2；第三次借用：抛出 IllegalStateException（pool exhausted）
lease.close_twice	同一个租约 close 两次：空闲资源 1 个
lease.use_after_close	关闭后再通过租约取资源：抛出 IllegalStateException（lease closed）
lease.exception	try-with-resources 中业务抛异常：空闲资源 2 个
lease.broken	id 2 在使用中损坏后归还：被替换 1 个；之后池里的资源是 id [1, 3]，不含 id 2 = true
EXPECTED
log "全部通过：11 行输出与预期逐行一致，输出在 $OUT"
