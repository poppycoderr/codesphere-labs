#!/usr/bin/env bash
# W+R>N 之后还缺什么：版本比较、写入部分失败、带时钟偏差的最后写入获胜、版本向量、宽松 quorum、读自己的写（确定性模拟）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC src/QuorumLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 N=3
overlap.compare	W=2 写入 v2 到 A、B（C 仍是 v1）；R=2 从 C、B 读，比较版本取最新：v2
overlap.first	同样从 C、B 读，取最先返回的 C：v1
no_overlap	W=1 写入 A，R=1 从 C 读：v1
partial_write	W=2 写 v3，只有 A 确认（1 个），客户端收到失败；随后 R=2 从 A、C 读：v3；从 B、C 读：v1：一次「失败」的写，有时读得到，有时读不到
lww.skew	Y 在真实时间 95 写入，X 在真实时间 100 写入但时钟慢 10：最后写入获胜保留的是「Y 写的 2」，X 后发生的写入被静默丢弃
vv.concurrent	两个客户端都基于 {A:1} 写入，版本向量 {A:2} 与 {A:1,B:1} 互不包含：副本保留 [x=1, x=2]，交给读的一方合并
vv.merge	读到两个值、合并为 x=3 并以 {A:3,B:1} 写回：副本保留 [x=3]
sloppy	首选副本 A、B、C；A、B 不可达时写入落在 C 和备用节点 D（确认 2 个）；A、B 恢复后、提示移交完成前，R=2 从 A、B 读：v1：W+R=4>3，读写集合却不相交
ryw.plain	会话写入 v5（W=2 到 A、B），下一次 R=1 被路由到 C：v1
ryw.token	会话记住自己写入的版本 5，读到更旧的版本就换副本：拒绝（版本 1 < 5）→ 改读 A：v5
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
