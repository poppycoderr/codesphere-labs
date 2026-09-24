#!/usr/bin/env bash
# 包装类模式：适配器的错误翻译与重试、装饰器顺序对计量与缓存的影响、JDK 动态代理的自调用与异常包装
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh

OUT="${1:-build/run}"
require_java 21
mkdir -p "$OUT" build/classes; find "$OUT" -mindepth 1 ! -name README.md -delete
javac -encoding UTF-8 -d build/classes src/Wrappers.java
java -cp build/classes Wrappers >"$OUT/output.tsv"
write_environment "$OUT/environment.txt"

expect_line "$OUT/output.tsv" "adapter.flatten+retry_all	成功 8，失败 {SmsFailed:短信发送失败=2}，供应商调用 17 次" "全部翻译成同一异常：无效号码也被重试，供应商调用 17 次"
expect_line "$OUT/output.tsv" "adapter.precise+retry_retryable	成功 8，失败 {PermanentFailure:INVALID_NUMBER=2}，供应商调用 13 次" "区分可重试与不可重试：调用 13 次，保留供应商错误码"
expect_line "$OUT/output.tsv" "order.retry(cache(metrics(delegate)))	metrics 计数 10、错误 5；缓存命中 5；下游调用 10" "计量在最内层：记录到 5 个错误，缓存命中不计入"
expect_line "$OUT/output.tsv" "order.metrics(cache(retry(delegate)))	metrics 计数 10、错误 0；缓存命中 5；下游调用 10" "计量在最外层：10 次请求 0 错误"
expect_line "$OUT/output.tsv" "proxy.self_invocation	sendAll 发送 3 条，经过代理的调用 1 次" "自调用绕过代理"
expect_line "$OUT/output.tsv" "调用方收到 UndeclaredThrowableException，原因 IOException" "未声明的受检异常被包装"
log "全部通过，输出在 $OUT"
