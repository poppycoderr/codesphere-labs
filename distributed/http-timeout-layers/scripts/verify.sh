#!/usr/bin/env bash
# JDK HttpClient 的 connectTimeout 与请求 timeout 各管哪一段：拒绝连接、SYN 没有回应、TLS 握手卡住、响应头卡住、响应体卡住；
# 服务端已提交但客户端超时时，重试带与不带幂等键。服务端与客户端在同一个容器里（需要 Linux 的 SYN 丢弃行为），JDK 21 与 25 各跑一遍
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"
JDK21=eclipse-temurin@sha256:78ab9771b4650066c3ef748d46e05dbd6094d8bb34e0667a074486812efd655b
JDK25=eclipse-temurin@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2
for v in 21 25; do
  img=JDK$v
  log "JDK $v"
  docker run --rm -v "$PWD/src:/src:ro" "${!img}" java /src/Timeouts.java 2>/dev/null | awk -F'\t' 'NF>1 && $1 ~ /^[a-z]/' >"$OUT/jdk$v.tsv"
done
write_environment "$OUT/environment.txt" "image_jdk21: $JDK21" "image_jdk25: $JDK25"
python3 - "$OUT" <<'PY'
import re, sys, pathlib
out = pathlib.Path(sys.argv[1])
for v in ("21", "25"):
    rows = dict(l.split("\t", 1) for l in (out / f"jdk{v}.tsv").read_text(encoding="utf-8").splitlines())
    def ms(k):
        return int(re.search(r"耗时 (\d+) ms", rows[k])[1])
    def check(ok, msg):
        if not ok:
            sys.exit(f"失败（JDK {v}）：{msg}\n" + "\n".join(f"{k}\t{x}" for k, x in rows.items()))
        print(f"通过（JDK {v}）：{msg}")
    check(rows["refused"].startswith("ConnectException") and ms("refused") < 500, "端口没有监听：立刻 ConnectException")
    check(rows["syn-dropped"].startswith("HttpConnectTimeoutException") and 1000 <= ms("syn-dropped") < 1500, "SYN 没有回应：1 秒 connectTimeout 触发")
    check(rows["tls-stalled"].startswith("HttpConnectTimeoutException") and 1000 <= ms("tls-stalled") < 1500, "TCP 已连上、TLS 握手卡住：同样由 connectTimeout 在 1 秒触发")
    check(rows["tls-stalled-no-request-timeout"].startswith("HttpConnectTimeoutException"), "TLS 握手卡住与请求 timeout 无关")
    check(rows["headers-stalled"].startswith("HttpTimeoutException") and 2000 <= ms("headers-stalled") < 2500, "响应头卡住：2 秒请求 timeout 触发")
    check(rows["headers-stalled-no-request-timeout"].startswith("HTTP 200") and ms("headers-stalled-no-request-timeout") >= 5000, "不设请求 timeout：一直等到服务端 5 秒后响应")
    check(rows["body-stalled"].startswith("HTTP 200 1000") and ms("body-stalled") >= 5000, "响应头已到、响应体卡住：2 秒请求 timeout 不起作用，等了 5 秒")
    check("截止时间到" in rows["body-stalled-with-deadline"] and 3000 <= ms("body-stalled-with-deadline") < 3500, "整次调用 3 秒截止并取消：响应体卡住时按时返回")
    check("服务端实际预留名额 2 次" in rows["reserve.nokey"], "服务端已提交、客户端超时后重试：不带幂等键预留了 2 次")
    check("重试 HTTP 200" in rows["reserve.key"] and "服务端实际预留名额 1 次" in rows["reserve.key"], "带幂等键：重试拿到第一次的结果，只预留 1 次")
PY
log "全部通过，输出在 $OUT"
