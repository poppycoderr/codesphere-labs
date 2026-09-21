#!/usr/bin/env bash
# 通知路由案例的核心验证：编译 → 10 个 JUnit 测试 → 内部 DSL 示例 → 影子比对 → 依赖方向检查
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-build/run}"
require_java 21
JUNIT=$(junit_jar)
rm -rf build/classes && mkdir -p build/classes "$OUT"

log "编译"
javac -encoding UTF-8 -cp "$JUNIT" -d build/classes src/*.java tests/*.java

log "运行 JUnit 测试"
java -jar "$JUNIT" execute -cp build/classes --select-class RouterTest \
  --details=tree --disable-banner --disable-ansi-colors 2>&1 \
  | sed -E 's/Test run finished after [0-9]+ ms/Test run finished after <elapsed> ms/' >"$OUT/test-output.txt"

log "运行内部 DSL 示例"
java -cp build/classes Dsl >"$OUT/dsl-output.txt"

log "运行影子比对（10,000 条合成告警，固定随机种子 42）"
java -cp build/classes Shadow >"$OUT/shadow-output.txt"

log "检查核心模型的依赖方向"
javap -c -p -cp build/classes Router >"$OUT/router-bytecode.txt"
{
  echo "# Router 字节码中引用的外围类型（期望为空）"
  grep -oE 'Channel|Suppression|Dispatcher|RuleText|Clock|LegacyAlertService' "$OUT/router-bytecode.txt" | sort -u || true
} >"$OUT/router-dependencies.txt"
rm "$OUT/router-bytecode.txt"

write_environment "$OUT/environment.txt" "junit_platform: $JUNIT_VERSION"

# 断言：正文中的关键结论
expect_line "$OUT/test-output.txt" "[        10 tests successful      ]" "10 个测试全部通过"
expect_line "$OUT/test-output.txt" "[         0 tests failed          ]" "没有失败的测试"
expect_line "$OUT/shadow-output.txt" "== 第一次影子比对（严格翻译）：一致 9269 / 10000" "第一轮一致 9,269 条"
expect_line "$OUT/shadow-output.txt" "   316  新系统无法识别 severity=critical" "critical 写法 316 条"
expect_line "$OUT/shadow-output.txt" "   320  旧系统多发 [email]（team=payment）" "夜间邮件 320 条"
expect_line "$OUT/shadow-output.txt" "== 第二次（翻译层兼容大小写与 critical）：一致 9617 / 10000" "第二轮一致 9,617 条"
expect_line "$OUT/shadow-output.txt" "== 第三次（夜间邮件确认下线，登记为有意差异）：一致 10000 / 10000" "第三轮全部一致"
expect_line "$OUT/dsl-output.txt" "[Target[channel=phone, address=oncall-payment], Target[channel=chat, address=payment-alerts]]" "DSL 构造的路由与代码定义一致"
[ "$(grep -vc '^#' "$OUT/router-dependencies.txt")" = 0 ] || fail "Router 依赖了外围类型：$(cat "$OUT/router-dependencies.txt")"
log "通过：Router 不依赖渠道、抑制策略、规则文本与时钟"
log "全部通过，输出在 $OUT"
