#!/usr/bin/env bash
# 从模型到部署：模块化单体里的上下文边界规则；同一组报名用例在同进程与 HTTP 拆分两种部署下运行；拆分后才出现的失败方式
# 用法：scripts/verify.sh [输出目录]，默认 target/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-target/run}"
require_java 21
require mvn python3
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
rm -f target/facts.tsv

log "运行 JUnit 测试（mvn test，约 15 秒）"
mvn -B -q -Dstyle.color=never test >"$OUT/maven-test.log" 2>&1 || { cat "$OUT/maven-test.log"; fail "测试失败"; }
python3 ../../shared/scripts/surefire-summary.py target/surefire-reports "$OUT/test-results.txt"
sort target/facts.tsv >"$OUT/facts.tsv"
javap -c -p target/test-classes/labs/ddd/constleak/registration/RegistrationMessages.class | sed -n '/confirmed(java.lang.String)/,/areturn/p' >"$OUT/constant-inlined.javap.txt"
write_environment "$OUT/environment.txt" "maven: $(mvn -v | head -1 | awk '{print $3}')" "archunit: 1.5.0" "junit: 5.13.4"
normalize_paths <"$OUT/maven-test.log" >"$OUT/maven-test.log.tmp" && mv "$OUT/maven-test.log.tmp" "$OUT/maven-test.log"

expect_line "$OUT/test-results.txt" "tests=11 failures=0" "11 个测试全部通过"
expect_line "$OUT/facts.tsv" "报名模块只依赖通知 api" "主代码满足模块边界规则"
expect_line "$OUT/facts.tsv" "calls method <labs.ddd.leak.notification.internal.SmsTemplates.confirmed(java.lang.String)>" "调用通知模块内部方法被拦下"
expect_line "$OUT/facts.tsv" "只引用对方的 static final String 常量：违规 0 条" "编译期常量被内联，规则看不到这条依赖"
expect_line "$OUT/constant-inlined.javap.txt" "// String 您已报名 %s" "字节码里是内联后的字符串"
for m in in-process http; do for n in 1 2 3; do grep -q "^same.$m.$n	" "$OUT/facts.tsv" || fail "缺少 same.$m.$n"; done; done
log "通过：同一组 3 个用例在两种部署下都通过"
expect_line "$OUT/facts.tsv" "通知服务停止：20 次报名失败 20 次（ConnectException），已确认 0" "同步调用：通知服务停止时报名全部失败"
expect_line "$OUT/facts.tsv" "5 次报名失败 5 次，已确认 0，HTTP 调用 15 次，通知服务实际发出短信 15 条" "超时重试：报名回滚了，短信却发了 15 条"
expect_line "$OUT/facts.tsv" "5 次报名失败 5 次，已确认 0，HTTP 调用 15 次，通知服务实际发出短信 5 条" "按 noticeId 去重后 5 条，但报名仍全部回滚"
expect_regex "$OUT/facts.tsv" "同进程 [0-9]+µs，本机 HTTP [0-9]+µs" "记录同进程与 HTTP 调用的中位耗时"
expect_line "$OUT/facts.tsv" "通知服务日志：notification traceId=trace-7f3a noticeId=<uuid>" "traceId 跨进程传递"
log "全部通过，输出在 $OUT"
