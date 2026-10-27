#!/usr/bin/env bash
# 部分成功回退、加锁顺序、租期预算与多数派的前提（JDK 25，模拟租约锁服务与可控时钟）
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
partial.naive	锁 A 成功=true、锁 B 失败=true，朴素实现直接返回失败：acct:A 的持有者仍是「转账-1」，要等 10 秒租期到期才释放
partial.rollback	第二把失败时逆序释放已拿到的：acct:A 的持有者 无
partial.release-fails	回退本身也可能失败：释放失败（释放 acct:A 时网络超时），acct:A 现在是「转账-1」，10 秒后为「无」，所以每把锁都必须有租期兜底
order.opposite	线程一先 A 后 B，线程二先 B 后 A，都拿到第一把后等第二把：500ms 后两个线程都还在等=true
order.sorted	两个线程请求的顺序相反，但都先按名字排序再加锁：完成 2 个，没有互相等待
lease.budget	逐个获取三把锁、每次耗时 3 秒、租期 10 秒：全部拿到时，第一把只剩 1 秒；业务需要 5 秒 → 放弃并释放（否则第一把会在业务中途过期）
lease.mid-operation	如果不检查直接执行 5 秒：执行结束时 stock:1 的持有者是「无」
majority.restart	客户端一在 3 个节点里拿到 2 个；节点 2 丢失状态后立即重启，客户端二拿到 2 个：两个客户端都认为自己持有锁
majority.note	多数派获取依赖「节点重启后等待一个租期再服务」等前提；它不是共识复制，也不给资源端提供 fencing 令牌
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
