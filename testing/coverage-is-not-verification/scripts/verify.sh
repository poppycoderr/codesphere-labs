#!/usr/bin/env bash
# 覆盖率与变异测试：同一个运费函数，三套测试各跑一遍 JaCoCo 与 PIT；再跑一个针对未实现需求的测试
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.9.11 + temurin 25 容器；首次运行要下载依赖（缓存在仓库的 .cache/m2repo），约 2 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
MVN="maven@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c"
mkdir -p "$LABS_CACHE/m2repo" build
mvn_in() { docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -B -Dstyle.color=never "$@"; }

printf 'suite\tline_covered\tline_total\tbranch_covered\tbranch_total\tmutants\tkilled\tsurvived\n' >"$OUT/summary.tsv"
for s in NoAssertTest TypicalValueTest BoundaryTest; do
  mvn_in -q -Dsuite="labs.$s" clean test jacoco:report org.pitest:pitest-maven:mutationCoverage >"build/$s.log" 2>&1 || { tail -30 "build/$s.log" >&2; fail "$s 运行失败"; }
  # mutations.csv 的列：文件,类,变异算子,方法,行,状态,杀死它的测试
  awk -F, '{ n=split($3,a,"."); t=$7; sub(/.*method:/,"",t); sub(/\(\).*/,"",t); print a[n] "\t" $5 "\t" $6 "\t" t }' target/pit-reports/mutations.csv | sort -t "$(printf '\t')" -k2,2n -k1,1 >"$OUT/mutations-$s.tsv"
  awk -F, -v s="$s" 'NR==2 { lc=$9; lt=$8+$9; bc=$7; bt=$6+$7 } END { printf "%s\t%d\t%d\t%d\t%d\t", s, lc, lt, bc, bt }' target/site/jacoco/jacoco.csv >>"$OUT/summary.tsv"
  awk -F'\t' '{ n++; if ($3=="KILLED") k++; else sv = sv (sv==""?"":",") $1 "@" $2 } END { printf "%d\t%d\t%s\n", n, k, (sv==""?"-":sv) }' "$OUT/mutations-$s.tsv" >>"$OUT/summary.tsv"
done
# 需求里有、代码里没有的规则：测试失败，构建失败
set +e
mvn_in -Dsuite=labs.MissingRequirementTest clean test >build/missing.log 2>&1
code=$?
set -e
{ echo "maven_exit_code=$code"; grep -E "Tests run:|Expected java.lang|BUILD" build/missing.log | sed -E 's/, Time elapsed: [0-9.]+ s//; s/ -- Time elapsed: [0-9.]+ s//' | sort -u; } >"$OUT/missing-requirement.txt"
write_environment "$OUT/environment.txt" "maven_image: $MVN" "container_java: $(docker run --rm "$MVN" java -version 2>&1 | head -1)" \
  "junit-jupiter: 6.1.3" "jacoco: 0.8.15" "pitest: 1.30.0" "pitest-junit5-plugin: 1.2.3" "maven-surefire-plugin: 3.6.0"
cat "$OUT/summary.tsv" "$OUT/missing-requirement.txt" >&2

# 测试与变异都是确定的：与预期逐行比较
diff - "$OUT/summary.tsv" <<'EXPECTED' || fail "汇总与预期不一致"
suite	line_covered	line_total	branch_covered	branch_total	mutants	killed	survived
NoAssertTest	11	11	10	10	17	2	ConditionalsBoundaryMutator@9,ConditionalsBoundaryMutator@9,ConditionalsBoundaryMutator@12,NegateConditionalsMutator@12,ConditionalsBoundaryMutator@16,NegateConditionalsMutator@16,MathMutator@17,MathMutator@17,MathMutator@17,MathMutator@18,MathMutator@18,NegateConditionalsMutator@20,MathMutator@21,MathMutator@21,PrimitiveReturnsMutator@23
TypicalValueTest	11	11	10	10	17	14	ConditionalsBoundaryMutator@9,ConditionalsBoundaryMutator@12,ConditionalsBoundaryMutator@16
BoundaryTest	11	11	10	10	17	16	ConditionalsBoundaryMutator@16
EXPECTED
[ "$code" != 0 ] || fail "针对未实现需求的测试应当让构建失败"
expect_regex "$OUT/missing-requirement.txt" "Tests run: 1, Failures: 1" "未实现的需求：测试失败"
expect_regex "$OUT/missing-requirement.txt" "BUILD FAILURE" "未实现的需求：构建失败"
log "全部通过，输出在 $OUT"
