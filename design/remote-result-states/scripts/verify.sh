#!/usr/bin/env bash
# 成功、确实不存在、调用失败、结果未知：有类型结果对比 null/空列表式客户端（JDK 25 HttpClient + 内置 HttpServer）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 15 秒（含模拟的慢响应）
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
single.ok	商品 9 不存在：朴素客户端 Optional.empty，有类型的客户端 确实不存在
single.busy	商品服务降级，返回 200 + {code: SYSTEM_BUSY, data: null}：朴素客户端只看 HTTP 状态码 → Optional.empty（当成不存在），有类型的客户端 → 调用失败（业务码 SYSTEM_BUSY）
single.timeout	商品服务超时：朴素客户端 → Optional.empty，有类型的客户端 → 调用失败（超时）
cleanup	清理购物车里已下架的商品，商品服务超时：朴素客户端拿到空列表，删除了 3 项（全部）；有类型的客户端 跳过这一轮清理（调用失败（超时））
batch.zip	按运营配置的顺序 [3, 1, 9, 1] 批量查询，服务端按主键返回且跳过不存在的：按下标对应 3→键盘，1→显示器，9→（缺），1→（缺）；按 id 对应 3→显示器，1→键盘，9→已下架，1→键盘
write.naive	下单请求超时被当成失败、直接重试一次：服务端实际创建了 2 个订单
write.unknown	超时当作「结果未知」：带幂等键查询后得知 已创建（order-3），服务端共创建 1 个订单
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
