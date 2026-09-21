#!/usr/bin/env bash
# Spring AOP 代理失效场景：真实 Spring 容器 + JUnit 断言 + 应用日志
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3
mkdir -p "$OUT"

log "运行 JUnit 测试（mvn test）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }

log "整理测试报告（只保留用例名、结果和耗时，去掉 surefire 报告里的系统属性）"
python3 - target/surefire-reports "$OUT/test-results.txt" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
rows, total, bad = [], 0, 0
for f in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
    for tc in ET.parse(f).getroot().iter("testcase"):
        total += 1
        failed = tc.find("failure") is not None or tc.find("error") is not None
        bad += failed
        rows.append(f"{'FAIL' if failed else 'PASS'}  {tc.get('classname').split('.')[-1]}  {tc.get('name')}")
with open(sys.argv[2], "w", encoding="utf-8") as out:
    out.write("# 由 scripts/verify.sh 从 target/surefire-reports/TEST-*.xml 生成\n")
    out.write("\n".join(rows) + f"\n\ntests={total} failures={bad}\n")
PY

log "运行演示程序，保存应用日志"
mvn -B -q -Dstyle.color=never compile exec:java 2>&1 \
  | sed -E 's/^[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4} [0-9:]+ [AP]M /<timestamp> /' >"$OUT/application.log"

log "记录解析后的依赖版本"
mvn -B -q -Dstyle.color=never dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dependencies.txt" >/dev/null
sed -i.bak -E 's/ -- module .*//' "$OUT/dependencies.txt" && rm -f "$OUT/dependencies.txt.bak"

write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')"
normalize_paths <"$OUT/maven-test.log" >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

# 断言
expect_line "$OUT/test-results.txt" "tests=12 failures=0" "12 个测试全部通过"
expect_line "$OUT/application.log" "Spring 7.0.9" "Spring Framework 版本为 7.0.9"
expect_line "$OUT/application.log" "    persist()：没有事务" "自调用没有事务"
expect_line "$OUT/application.log" "    repo = null" "final 方法在代理对象上执行，repo 为 null"
expect_line "$OUT/application.log" "    asyncThread()：没有事务" "切换线程后没有事务"
expect_line "$OUT/application.log" "抛出 NoSuchBeanDefinitionException" "JDK 代理下按实现类取 Bean 失败"
expect_line "$OUT/dependencies.txt" "org.springframework:spring-context:jar:7.0.9" "依赖解析到 spring-context 7.0.9"
log "全部通过，输出在 $OUT"
