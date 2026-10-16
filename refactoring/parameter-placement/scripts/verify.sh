#!/usr/bin/env bash
# 参数放在哪里：请求数据放进单例字段、同类型参数写反、线程上下文没有清理
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS=""
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/ParamLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
field.shared	单例服务把当前用户放在字段里，alice 的请求等 I/O 时 bob 的请求进来：alice 收到「hello bob」
field.param	作为参数传入：alice 收到「hello alice」
swap.raw	count(from, to) 正确调用 2 条；参数写反成 count(oct10, oct1)：编译通过，返回 0 条，没有任何错误
swap.object	换成参数对象 Period(from, to)，写反时：构造时抛出 IllegalArgumentException（from 必须早于 to：2026-10-10T00:00:00Z / 2026-10-01T00:00:00Z）
context.leak	线程池里的上一个任务设置了租户、没有清理；同一个线程上的下一个任务读到：tenant-a
context.cleaned	改成 try/finally 里 remove：下一个任务读到 null
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
