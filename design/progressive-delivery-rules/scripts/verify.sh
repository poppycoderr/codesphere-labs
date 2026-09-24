#!/usr/bin/env bash
# 渐进发布规则：确定性分桶（比例、单调、稳定、加盐）、优先级与冲突检测、配置校验与回滚、多节点传播期间的结果跳变
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-build/run}"
require_java 21
mkdir -p "$OUT" build/classes; find "$OUT" -mindepth 1 ! -name README.md -delete
javac -encoding UTF-8 -d build/classes src/Rollout.java
java -cp build/classes Rollout >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"

expect_line "$OUT/output.tsv" "bucket.share	10 万用户：10% 规则命中 10066，20% 规则命中 19977" "确定性分桶的比例接近配置值"
expect_line "$OUT/output.tsv" "bucket.monotonic	10% 命中的用户在 20% 时仍命中：true" "放量单调"
expect_line "$OUT/output.tsv" "bucket.stable	重新加载同一配置后结果一致的用户 100000 / 100000" "重新加载后结果不变"
expect_line "$OUT/output.tsv" "bucket.random	按随机数决定：1 万个用户各请求两次，两次结果不同 1772 人" "随机数写法：同一用户两次结果不同"
expect_line "$OUT/output.tsv" "bucket.salted	两个 10% 的开关，以开关名加盐：同时命中 1027 人；不加盐：同时命中 10085 人" "不加盐时不同开关命中同一批用户"
expect_line "$OUT/output.tsv" "umbrella 用户 → Decision[enabled=false, ruleId=deny-umbrella, version=3]" "高优先级的拒绝规则胜出"
expect_line "$OUT/output.tsv" "rule.conflict	规则 allow-acme 与 deny-de 优先级相同、条件重叠、结果相反" "加载时发现冲突规则"
expect_line "$OUT/output.tsv" "config.rejected	规则 ramp 的比例 120 不在 0—100 之间；继续使用版本 1" "非法配置被拒绝并保留旧版本"
expect_line "$OUT/output.tsv" "与版本 1 一致的用户 100000 / 100000" "回滚后分配结果与版本 1 完全一致"
expect_line "$OUT/output.tsv" "propagation.flip	1 万个用户在 60 秒内各请求 6 次：结果来回变化（超过一次）的用户 3961 人" "配置传播期间部分用户的结果来回变化"
log "全部通过，输出在 $OUT"
