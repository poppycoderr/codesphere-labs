#!/usr/bin/env bash
# 拆分测试时的覆盖责任：同一组测试在正常代码与两个故意引入的缺陷下各跑一次，看谁能发现缺陷；另验证 JUnit 动态测试不为每个子测试执行 @BeforeEach（JUnit 6.1.3、JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 + temurin 25 容器（运行三次测试）与 temurin 25 容器（汇总报告）；约 2 分钟（首次需下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"; echo "env	java.version=25.0.4.1 junit=6.1.3" >"$f"
mkdir -p build/runs
for run in correct ignoreQualifier noMapperFallback; do
  rm -rf target build/runs/$run
  # 有的测试按设计会失败，测试失败不中断构建，结果由报告逐条比较
  docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B test -Dmaven.test.failure.ignore=true -Dlab.mutant=$run >"$OUT/maven-$run.log" 2>&1 || fail "Maven 执行失败（${run}），见 $OUT/maven-$run.log"
  mkdir -p build/runs/$run; cp -r target/surefire-reports build/runs/$run/reports; [ -d target/lab-notes ] && cp -r target/lab-notes build/runs/$run/lab-notes
  docker run --rm -v "$PWD:/w" -w /w "$J25" java src/Report.java 1 build/runs/$run/reports $run >>"$f"
done
write_environment "$OUT/environment.txt" "maven_image: $MVN" "jdk25_image: $J25" "junit: 6.1.3"
cat "$f" >&2
# 测试结果与失败信息是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 junit=6.1.3
correct.ContainerTest.真实容器按限定符分别装配 raw 与 daily	通过
correct.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 a	通过
correct.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 b	失败：expected: <[b]> but was: <[a, b]>
correct.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 c	失败：expected: <[c]> but was: <[a, b, c]>
correct.DynamicLifecycleTest.普通测试：每次都有新的列表	通过
correct.ErrorResponderTest.映射器执行失败时回落到 500（名不副实：实际让查找失败）	通过
correct.ErrorResponderTest.映射器执行失败时回落到 500（真正让映射器抛异常）	通过
correct.ErrorResponderTest.查找映射器失败时回落到 500	通过
correct.InjectionRuleTest.注入规则向 Context 要 Source@raw 与 Source@daily	通过
correct.note.before_each_runs	@BeforeEach 共执行 2 次（1 个普通测试 + 1 个测试工厂，工厂产生了 3 个动态子测试）
ignoreQualifier.ContainerTest.真实容器按限定符分别装配 raw 与 daily	失败：expected: <raw> but was: <daily>
ignoreQualifier.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 a	通过
ignoreQualifier.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 b	失败：expected: <[b]> but was: <[a, b]>
ignoreQualifier.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 c	失败：expected: <[c]> but was: <[a, b, c]>
ignoreQualifier.DynamicLifecycleTest.普通测试：每次都有新的列表	通过
ignoreQualifier.ErrorResponderTest.映射器执行失败时回落到 500（名不副实：实际让查找失败）	通过
ignoreQualifier.ErrorResponderTest.映射器执行失败时回落到 500（真正让映射器抛异常）	通过
ignoreQualifier.ErrorResponderTest.查找映射器失败时回落到 500	通过
ignoreQualifier.InjectionRuleTest.注入规则向 Context 要 Source@raw 与 Source@daily	通过
ignoreQualifier.note.before_each_runs	@BeforeEach 共执行 2 次（1 个普通测试 + 1 个测试工厂，工厂产生了 3 个动态子测试）
noMapperFallback.ContainerTest.真实容器按限定符分别装配 raw 与 daily	通过
noMapperFallback.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 a	通过
noMapperFallback.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 b	失败：expected: <[b]> but was: <[a, b]>
noMapperFallback.DynamicLifecycleTest.三个动态子测试，各自期望列表里只有自己的一项 子测试 c	失败：expected: <[c]> but was: <[a, b, c]>
noMapperFallback.DynamicLifecycleTest.普通测试：每次都有新的列表	通过
noMapperFallback.ErrorResponderTest.映射器执行失败时回落到 500（名不副实：实际让查找失败）	通过
noMapperFallback.ErrorResponderTest.映射器执行失败时回落到 500（真正让映射器抛异常）	错误：映射器坏了
noMapperFallback.ErrorResponderTest.查找映射器失败时回落到 500	通过
noMapperFallback.InjectionRuleTest.注入规则向 Context 要 Source@raw 与 Source@daily	通过
noMapperFallback.note.before_each_runs	@BeforeEach 共执行 2 次（1 个普通测试 + 1 个测试工厂，工厂产生了 3 个动态子测试）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
