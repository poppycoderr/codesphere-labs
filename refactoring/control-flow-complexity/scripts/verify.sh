#!/usr/bin/env bash
# 控制流与复杂度：同一条运费规则的五种写法，PMD 7.28.0 报出的圈复杂度、认知复杂度、NPath，以及 48 组输入下的结果一致性
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；PMD 发行包从 GitHub 下载到缓存目录（约 50 MB）；约 30 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker curl unzip
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PMD_VERSION=7.28.0
PMD_HOME="$LABS_CACHE/pmd/pmd-bin-$PMD_VERSION"
if [ ! -d "$PMD_HOME" ]; then
  mkdir -p "$LABS_CACHE/pmd"
  curl -fsSL -o "$LABS_CACHE/pmd/pmd.zip" "https://github.com/pmd/pmd/releases/download/pmd_releases%2F$PMD_VERSION/pmd-dist-$PMD_VERSION-bin.zip" || fail "下载 PMD 失败"
  (cd "$LABS_CACHE/pmd" && unzip -q pmd.zip && rm pmd.zip)
fi
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -v "$PMD_HOME:/pmd:ro" -w /w "$J25" sh -c '/pmd/bin/pmd check --no-cache -d src/Shipping.java -R src/rules.xml -f text 2>/dev/null; java src/Shipping.java 2>/dev/null' >"$OUT/raw.txt" || true
# 把 PMD 的文本报告整理成「方法、度量、值」三列；main 方法不计入
sed -nE "s#^.*(Cyclomatic|Cognitive|NPath)Complexity:.*method '([a-z]+)\(.*(complexity of|NPath complexity of) ([0-9]+).*#\2\t\1\t\4#p" "$OUT/raw.txt" | grep -v '^main' >"$f"
grep -E '^(env|equivalence)' "$OUT/raw.txt" >>"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "pmd: $PMD_VERSION"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
nested	Cognitive	16
nested	Cyclomatic	6
nested	NPath	9
guarded	Cognitive	7
guarded	Cyclomatic	7
guarded	NPath	19
switched	Cognitive	5
switched	Cyclomatic	7
switched	NPath	9
table	Cognitive	4
table	Cyclomatic	5
table	NPath	7
base	Cyclomatic	1
base	NPath	1
strategy	Cognitive	2
strategy	Cyclomatic	3
strategy	NPath	3
env	java.version=25.0.4.1
equivalence	48 组输入（3 个地区 × 4 个金额 × 是否会员 × 是否超重），另外四种写法与嵌套写法不一致的次数：0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
