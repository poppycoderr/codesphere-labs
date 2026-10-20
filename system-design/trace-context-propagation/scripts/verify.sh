#!/usr/bin/env bash
# 追踪上下文在哪一跳丢失：线程池、CompletableFuture、虚拟线程、包装后的执行器、手写 HTTP 调用的 traceparent、采样标志、未结束的 span（OpenTelemetry Java 1.67.0、JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 容器下载依赖，temurin 25 容器在进程内运行 OpenTelemetry SDK 与 JDK HttpServer；约 30 秒（首次需下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
rm -rf build/lib; mkdir -p build/lib
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B dependency:copy-dependencies -DoutputDirectory=/w/build/lib >/dev/null 2>&1 || fail "依赖下载失败"
ls build/lib | sort >"$OUT/classpath.txt"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" sh -c 'java -Duser.timezone=UTC -cp "$(ls build/lib/*.jar | tr "\n" ":")" src/Lab.java' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "opentelemetry-sdk: 1.67.0"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	opentelemetry-sdk=1.67.0 java=25.0.4.1+1-LTS
same_thread	同一线程里创建子 span：同一条 trace，parent 正确
pool.plain	提交到普通线程池：新的 trace（子 span 成了根）
pool.wrapped	提交到 Context.taskWrapping 包装的线程池：同一条 trace，parent 正确
cf.async	CompletableFuture.runAsync（公共池）：新的 trace（子 span 成了根）
cf.wrapped	CompletableFuture.runAsync 传入包装后的线程池：同一条 trace，parent 正确
virtual	Thread.ofVirtual().start：新的 trace（子 span 成了根）
context.wrap	普通线程池，但提交前用 Context.current().wrap(task)：同一条 trace，parent 正确
http.plain	手写 HttpClient 调用，不注入请求头：服务端收到 traceparent=null；服务端 span 是另一条 trace 的根
http.inject	注入 traceparent 后：服务端收到 traceparent=00-<traceId>-<spanId>-03；服务端 span 与客户端同一条 trace
sampling	根 span 采样器为 parentBased(alwaysOff)：服务端收到 traceparent 末尾标志=02（采样位为 0，随机 trace-id 位为 1），客户端导出 0 个 span；服务端用默认的 parentBased(alwaysOn) 采样器，跟随父级不采样，导出 0 个
export.unended	开始但没有调用 end() 的 span，导出数=0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
