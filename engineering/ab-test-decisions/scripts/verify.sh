#!/usr/bin/env bash
# A/B 实验判断的模拟：A/A 的假阳性率、中途偷看、样本量与功效、显著结果的夸大、不显著的置信区间、分析单位、多指标、按天汇总
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14.8 容器，只用标准库，固定随机种子；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$PY" python src/ab_sim.py >"$f"
docker run --rm "$PY" python -VV >"$OUT/python-version.txt"
write_environment "$OUT/environment.txt" "python_image: $PY" "seed: 20261002"
cat "$f" >&2
expect_regex "$OUT/python-version.txt" "^Python 3\.14\.8 " "运行在 Python 3.14.8"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
def pct(key, pattern=r"([\d.]+)%"):
    line = re.search(rf"^{re.escape(key)}\t(.*)$", t, re.M)[1]
    return [float(x) for x in re.findall(pattern, line)]
aa = pct("aa.fixed")[-1]
assert 4.0 <= aa <= 6.0, aa                                   # 显著性水平 5%：A/A 里约 5% 被判为显著
p5, p10, p20 = pct("aa.peek_5")[-1], pct("aa.peek_10")[-1], pct("aa.peek_20")[-1]
assert 12 <= p5 < p10 < p20 <= 30 and p10 >= 3 * aa, (p5, p10, p20)   # 偷看次数越多，假阳性越高
need = int(re.search(r"每组需要 (\d+) 人", t)[1])
assert 14000 <= need <= 15500, need
pw = {n: pct(f"power.n_{n}")[0] for n in (2000, 10000, need)}
assert pw[2000] < 25 and 60 <= pw[10000] <= 68 and 77 <= pw[need] <= 84, pw
lift = {n: float(re.search(rf"^power\.n_{n}\t.*平均提升 ([\d.]+) 个百分点", t, re.M)[1]) for n in (2000, 10000, need)}
assert lift[2000] > 2.0 and lift[2000] > lift[10000] > lift[need] > 1.0, lift     # 功效越低，显著结果里的提升被夸大得越多
ns = pct("ci.not_significant")
width = float(re.search(r"平均宽 ([\d.]+) 个百分点", t)[1])
assert ns[0] > 75 and width > 3.0, (ns, width)
cov = pct("ci.coverage")[-1]
assert 94.0 <= cov <= 96.0, cov
visit, user = pct("unit.by_visit")[-1], pct("unit.by_user")[-1]
assert visit > 12 and 3.0 <= user <= 7.0, (visit, user)       # 把访问当独立样本：假阳性成倍上升
any_hit, bonf = pct("metrics.any")[0], pct("metrics.bonferroni")[-1]
assert 60 <= any_hit <= 68 and 3.0 <= bonf <= 7.0, (any_hit, bonf)
by_user, by_day = pct("daily.by_user")[-1], pct("daily.by_day")[-1]
assert 60 <= by_user <= 68 and by_day < 5, (by_user, by_day)
print(f"通过：A/A 假阳性 {aa}%，看 10 次 {p10}%；功效 {pw}；按访问 {visit}% 对按用户 {user}%；按天汇总 {by_day}% 对 {by_user}%")
PY
log "全部通过，输出在 $OUT"
