#!/usr/bin/env bash
# 多个产品共用一套流程：按产品分支、可替换任意步骤的流水线、平台骨架加声明的扩展点、对所有扩展跑的契约测试
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC src/PlatformLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
if.branches	共用流程里按产品判断的分支 5 处（三种产品）；新增「直播」时，每一处都要判断要不要加，按这段代码，漏改时直播会落到 else 按默认费率收手续费，也不会开通权限
pipeline.skip	流水线允许产品替换任一步骤，秒杀产品换掉了第 2 步：执行轨迹 [validate, fast-approve, pay]，riskChecked=false
skeleton.flow	平台骨架下单课程：轨迹 [prepare, risk, pay, fulfil]，riskChecked=true，手续费 10；产品只能实现 prepare、fee、fulfil 三个扩展点
skeleton.new_product	新增直播：注册一个扩展，平台代码不变；直播订单轨迹 [prepare, risk, pay, fulfil]
skeleton.unknown	没有注册扩展的产品下单：被拒绝（未注册的产品：GIFT_CARD），而不是落到某个默认分支
contract	平台对 5 个扩展跑手续费契约（0 ≤ 手续费 ≤ 金额，退款单反号）：失败 1 项 [GROUP_BUY 金额 -50 的手续费 3]
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
