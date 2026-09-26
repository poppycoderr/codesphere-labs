#!/usr/bin/env bash
# 依赖任务图：发布前校验（环、缺失、重复）、稳定顺序、并发上限下的调度、失败传播的两种策略、重试与幂等键
# 约 5 秒；耗时只断言与关键路径、并发上限的关系
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/DagRunner.java >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"
f="$OUT/output.tsv"
expect_line "$f" "重复节点: 通知准备；缺失节点: 提交 依赖的 发票 不存在；循环依赖: 名额预留 -> 资格校验 -> 名额预留；受环阻塞: [提交, 费用计算]" "四类配置错误分别报告，环只报真实路径"
expect_line "$f" "正常的报名图：没有错误" "正常的图通过校验"
expect_line "$f" "普通队列：声明顺序 [资格校验, 名额预留, 费用计算, 通知准备, 风控复核, 提交]，倒序声明 [资格校验, 名额预留, 风控复核, 通知准备, 费用计算, 提交]" "普通队列的顺序随声明顺序变化"
expect_line "$f" "按名称排序的 ready 集合：声明顺序 [资格校验, 名额预留, 费用计算, 通知准备, 风控复核, 提交]，倒序声明 [资格校验, 名额预留, 费用计算, 通知准备, 风控复核, 提交]" "排序的 ready 集合给出稳定顺序"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
runs = {int(a): (int(b), int(c)) for a, b, c in re.findall(r"并发上限 (\d)：总耗时 (\d+)ms，同时运行最多 (\d+) 个，全部成功=true", t)}
assert runs[1][0] >= 850 and 650 <= runs[2][0] < 800 and 450 <= runs[3][0] < 600, runs
assert all(runs[k][1] == k for k in runs), runs
print(f"通过：总耗时 上限1 {runs[1][0]}ms（≥ 850）、上限2 {runs[2][0]}ms（≥ 650）、上限3 {runs[3][0]}ms（关键路径 450）；同时运行数等于上限")
PY
expect_line "$f" "跳过后继：{资格校验=SUCCEEDED, 名额预留=SUCCEEDED, 费用计算=FAILED, 通知准备=SUCCEEDED, 风控复核=SUCCEEDED, 提交=SKIPPED}" "跳过后继：无关分支照常完成"
expect_line "$f" "立即取消：{资格校验=SUCCEEDED, 名额预留=SUCCEEDED, 费用计算=FAILED, 通知准备=CANCELLED, 风控复核=CANCELLED, 提交=SKIPPED}" "立即取消：运行中的分支被取消"
expect_line "$f" "不带幂等键：名额预留调用 2 次，实际占用名额 2 个" "没有幂等键时重试占用两个名额"
expect_line "$f" "带幂等键（run_id + 节点）：名额预留调用 2 次，实际占用名额 1 个" "幂等键让重试只生效一次"
log "全部通过，输出在 $OUT"
