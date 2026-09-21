#!/usr/bin/env bash
# 同一段下单逻辑拆分前后的测试对照：
#   拆分前只能写端到端测试（假价格服务 + 真实 MySQL），结果取决于运行时刻；注入的 22:00 边界 bug 发现不了
#   拆分后 8 个单元测试不依赖外部环境，能精确发现这个 bug
# 用 -Duser.timezone 把「当前本地时间」固定到白天（10 点）或晚间优惠时段（21 点），让结果可复现
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
JUNIT=$(junit_jar)
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
rm -rf build/classes && mkdir -p build/classes
for v in correct injected-bug; do
  javac -encoding UTF-8 -cp "$JUNIT" -d "build/classes/$v" $(find "src/$v" -name '*.java') tests/*.java
done

# zone_for <本地小时>：返回使当前本地时间落在该小时的 Etc/GMT 时区
zone_for() {
  python3 - "$1" <<'PY'
import sys, datetime
utc = datetime.datetime.now(datetime.timezone.utc).hour
off = (int(sys.argv[1]) - utc) % 24
off = off - 24 if off > 14 else off
print("UTC" if off == 0 else f"Etc/GMT{'-' if off > 0 else '+'}{abs(off)}")
PY
}
DAY=$(zone_for 10); EVENING=$(zone_for 21)

CID=$(mysql_up csl-separation)
junit_in_docker() {   # junit_in_docker <变体> <时区> <测试类>
  java_in_network "$CID" -Duser.timezone="$2" -jar "/cache/$(basename "$JUNIT")" execute \
    -cp "build/classes/$1:/cache/m2/$(basename "$DRIVER")" --select-class "$3" \
    --details=tree --disable-banner --disable-ansi-colors 2>&1 \
    | sed -E 's/after [0-9]+ ms/after <elapsed> ms/; s/Etc\/GMT[-+][0-9]+|UTC/<zone>/g' || true
}
junit_local() {       # junit_local <变体> <测试类>（拆分后的测试不需要数据库）
  java -jar "$JUNIT" execute -cp "build/classes/$1" --select-class "$2" \
    --details=tree --disable-banner --disable-ansi-colors 2>&1 | sed -E 's/after [0-9]+ ms/after <elapsed> ms/' || true
}

log "拆分前：白天运行端到端测试"
junit_in_docker correct "$DAY" BeforeTest >"$OUT/before-correct-day.txt"
log "拆分前：晚间优惠时段运行同一个测试"
junit_in_docker correct "$EVENING" BeforeTest >"$OUT/before-correct-evening.txt"
log "拆分前：注入 22:00 边界 bug 后白天运行"
junit_in_docker injected-bug "$DAY" BeforeTest >"$OUT/before-bug-day.txt"
log "拆分后：正确版本的 8 个测试"
junit_local correct PricingPolicyTest >"$OUT/after-correct.txt"
junit_local correct OrderCreationTest >>"$OUT/after-correct.txt"
log "拆分后：注入 bug 的版本"
junit_local injected-bug PricingPolicyTest >"$OUT/after-bug.txt"
junit_local injected-bug OrderCreationTest >>"$OUT/after-bug.txt"
{
  echo "day_local_hour: 10（运行时根据当前 UTC 时间换算为 Etc/GMT 时区）"
  echo "evening_local_hour: 21"
} >"$OUT/time-setup.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11" "junit_platform: $JUNIT_VERSION" "mysql_connector_java: 8.0.27"

expect_line "$OUT/before-correct-day.txt" "[         1 tests successful      ]" "拆分前：白天测试通过"
expect_line "$OUT/before-correct-evening.txt" "expected: <144.00> but was: <134.00>" "拆分前：同一测试在晚间变红"
expect_line "$OUT/before-bug-day.txt" "[         1 tests successful      ]" "拆分前：注入 bug 后测试仍然通过"
[ "$(grep -c 'tests successful' "$OUT/after-correct.txt")" = 2 ] && grep -q "\[         5 tests successful" "$OUT/after-correct.txt" && grep -q "\[         3 tests successful" "$OUT/after-correct.txt" || fail "拆分后的 8 个测试没有全部通过"
log "通过：拆分后 8 个测试全部通过"
expect_line "$OUT/after-bug.txt" "二十二点整不再优惠()" "拆分后：边界 bug 被定位到具体用例"
expect_line "$OUT/after-bug.txt" "[         1 tests failed          ]" "拆分后：恰好 1 个测试失败"
