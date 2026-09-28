#!/usr/bin/env bash
# Guava EventBus：异常隔离、DeadEvent、同步与异步投递、注册关系的强引用
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：宿主机 JDK 21；几秒钟（首次需要下载 Guava）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
CP="$(maven_jar com.google.guava:guava:33.5.0-jre):$(maven_jar com.google.guava:failureaccess:1.0.3)"
java -cp "$CP" src/EventBusBehavior.java 2>/dev/null >"$OUT/output.tsv"
write_environment "$OUT/environment.txt" "guava: 33.5.0-jre"
f="$OUT/output.tsv"
cat "$f" >&2
expect_line "$f" "A 收到 [A-created-1001]，C 收到 [C-created-1001]；交给 SubscriberExceptionHandler 的异常 1 个（订阅者 B 处理失败）；post 向调用方抛出：无" "一个订阅者抛异常，其他订阅者照常收到，post 不抛出"
expect_line "$f" "发布没有订阅者的事件后，DeadEvent 监听器收到 [NoSubscriber[x=hello]]" "没有订阅者的事件变成 DeadEvent"
expect_line "$f" "注册后置为 null 并 GC：对象仍然存活=true；unregister 后再 GC：对象仍然存活=false" "注册关系是强引用，unregister 后才能回收"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
s, a = map(int, re.search(r"EventBus\.post 返回耗时 (\d+) ms，AsyncEventBus\.post 返回耗时 (\d+) ms", t).groups())
assert s >= 200 and a < 50, (s, a)
print(f"通过：同步 post {s} ms，异步 post {a} ms")
PY
log "全部通过，输出在 $OUT"
