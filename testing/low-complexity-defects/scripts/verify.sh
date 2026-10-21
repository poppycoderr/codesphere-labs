#!/usr/bin/env bash
# 低复杂度不等于不需要测试：三个圈复杂度为 1 的计价方法（PMD 7.28.0 计算），常见输入全部通过，边界输入各暴露一个错误
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；PMD 发行包从 GitHub 下载到缓存目录（约 50 MB，与 control-flow-complexity 共用）；约 20 秒
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
docker run --rm -v "$PWD:/w" -v "$PMD_HOME:/pmd:ro" -w /w "$J25" sh -c '/pmd/bin/pmd check --no-cache -d src/Pricing.java -R src/rules.xml -f text 2>/dev/null; java src/Pricing.java 2>/dev/null' >"$OUT/raw.txt" || true
# 把 PMD 的文本报告整理成「方法、度量、值」三列；main 方法不计入
sed -nE "s#^.*(Cyclomatic|Cognitive|NPath)Complexity:.*method '([A-Za-z]+)\(.*(complexity of|NPath complexity of) ([0-9]+).*#\2\t\1\t\4#p" "$OUT/raw.txt" | grep -E '^(discounted|total|averageYuan)' >"$f"
grep -E '^(env|typical|boundary)' "$OUT/raw.txt" >>"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "pmd: $PMD_VERSION"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
discounted	Cyclomatic	1
discounted	NPath	1
total	Cyclomatic	1
total	NPath	1
averageYuan	Cyclomatic	1
averageYuan	NPath	1
env	java.version=25.0.4.1
typical	常见输入（不打折、小额、能整除）：3/3 通过
boundary	边界输入：0/3 通过；discounted(1999, 2, 10) 期望 3598 实际 0；total(2000000, 1500) 期望 3000000000 实际 -1294967296；averageYuan(1000, 6) 期望 1.67 实际 1.66
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
