#!/usr/bin/env bash
# 事件溯源的决定与重放：重放确定性、拒绝不产生事件、期望版本、投影检查点与副作用隔离、快照（JDK 25）
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
replay.naive	在演算里生成咨询编号、取当前时间：同一条事件流重建两次，咨询编号 C-1 与 C-2，开单时间 2026-10-27T09:01:00Z 与 2026-10-27T09:02:00Z，两次状态相等=false
replay.fixed	编号和时间在决定时写进事件：重建两次，咨询编号都是 C-1，两次状态相等=true
reject.1	Close[rating=7] → 拒绝（评分必须在 0 到 5 之间，收到 7），事件数 1 → 1
reject.2	Close[rating=4] → 接受，事件数 1 → 2
reject.3	Append[text=还有一个问题] → 拒绝（工单已关闭，不能再追加消息），事件数 2 → 2
reject.4	Close[rating=5] → 拒绝（工单已关闭），事件数 2 → 2
conflict.first	处理者 A 读到版本 2，关闭工单，追加成功，版本变为 3
conflict.second	处理者 B 也读到版本 2，追加消息被拒：期望版本 2，实际版本 3
conflict.retry	处理者 B 重新加载后再决定：工单已关闭，不能再追加消息；流中事件数 3
projection.naive	摘要与回访写在一个处理器里：首次处理后发出回访 1 封，为修复读模型从头重建后共 2 封
projection.split	摘要投影与回访策略分开订阅：重建摘要后内容相同=true，回访仍是 1 封（回访策略的检查点停在 5）
projection.duplicate	同一条消息事件投递两次：没有检查点时 T4 2 条消息、处理中；按全局位置跳过已处理的 T4 1 条消息、处理中
projection.view	当前摘要：T4 1 条消息、已关闭，评分 4；T5 1 条消息、处理中
snapshot	共 6 个事件；在版本 4 做快照，恢复时读快照加 2 个后续事件，结果与完整重放相等=true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
