#!/usr/bin/env bash
# 告警规则在数什么：increase 外推、for 过滤尖峰、pending 到 firing、keep_firing_for、数据断档与 absent、Alertmanager 分组、静默与通知失败
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Prometheus 3.15.1 与 Alertmanager 0.34.1 容器（固定 digest，不暴露端口）、python:3.14.8-slim 客户端容器；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
C=(docker compose -f compose.yaml)
trap 'rc=$?; "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; exit $rc' EXIT
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d >/dev/null 2>&1 || fail "容器启动失败"
f="$OUT/output.tsv"
docker run --rm --network csl-alert-rules_default --network-alias lab -v "$PWD:/w:ro" -w /w "$PY" python src/lab.py >"$f" 2>"$OUT/details.json"
write_environment "$OUT/environment.txt" "$( "${C[@]}" config --images | sed 's/^/image: /' | tr '\n' ' ')" "python_image: $PY"
cat "$f" >&2
# 时间相关的结论在 lab.py 里只断言范围，具体秒数与次数记在 details.json
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	prometheus=3.15.1，alertmanager=0.34.1，抓取与评估间隔 1 秒
spike.increase	计数器只增加了 8，increase(...[3s]) 的最大值大于 8（外推到窗口边界）=True
spike.nofor	没有 for 的规则进入 firing=True
spike.for	for: 5s 的规则：进入过 pending=True，进入过 firing=False
silence	被静默的 SpikeNoFor 在 Alertmanager 里的状态是 suppressed=True
sustained.for	for: 5s 从 pending 到 firing 用了 4 到 7 秒=True
sustained.keep	错误停止后，keep_firing_for: 8s 的规则比普通规则多 firing 6 到 10 秒=True
gap.before	断档前 GapErrors 的状态：firing
gap.after	序列消失后 GapErrors 回到 inactive=True，absent 规则进入 firing=True
notify.group	team=a 的 webhook 收到的通知：[['SustainedFor5s', 'SustainedKeep']]
notify.broken	team=b 的 webhook 一直返回 500：收到过请求=True，alertmanager_notifications_failed_total 大于 0=True，GapAbsent 在 Prometheus 里仍是 firing=True
EXPECTED
log "全部通过，输出在 $OUT"
