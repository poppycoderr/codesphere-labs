#!/usr/bin/env bash
# 对象创建与生命周期：Builder 的校验与防御性复制、单例的作用域、Spring 作用域、浅复制与聚合复制
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"

mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=6 failures=0" "6 个测试全部通过"
expect_line "$OUT/facts.tsv" "loose.invalid_built	connect=PT5S read=PT1S" "不校验的 Builder 造出了连接超时大于读超时的对象"
expect_line "$OUT/facts.tsv" "loose.channels_after_builder_reused	[sms, email]" "不复制的 Builder：build 之后再改 Builder，已建成的对象跟着变"
expect_line "$OUT/facts.tsv" "strict.build_error	connectTimeout PT5S > readTimeout PT1S" "交给 record 紧凑构造器的 Builder 在 build 时拒绝"
expect_line "$OUT/facts.tsv" "strict.channels_after_builder_reused	[sms]" "record 复制了渠道列表"
expect_line "$OUT/facts.tsv" "计数分别为 3 与 1" "两个类加载器各有一个静态单例"
expect_line "$OUT/facts.tsv" "直接注入：每次都是同一个 prototype 实例" "注入 singleton 的 prototype 不会再变"
expect_line "$OUT/facts.tsv" "copy.shallow_polluted_original	[alice, mallory]" "浅复制改动污染了原对象"
expect_line "$OUT/facts.tsv" "仓储中只有 1 条" "保留 id 的深复制被保存时覆盖了原报名"
expect_line "$OUT/facts.tsv" "copy.copy_as_new	新 id 102、version 1、未发布事件 0 个" "以模板新建：新身份、新版本、不带事件"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-context:jar:7.0.9" "spring-context 7.0.9"
log "全部通过，输出在 $OUT"
