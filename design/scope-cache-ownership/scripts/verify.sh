#!/usr/bin/env bash
# 单例作用域的缓存所有权：浅复制配置跨容器共享缓存、限定符别名共享实例、无锁并发双重创建、null 缓存歧义、失败不缓存、装饰器转发依赖元数据（JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
shallow	同一份配置浅复制出两个容器：容器一的 clock 是 #1，容器二的 clock 是 #1（两个容器共享一个缓存）
owned	每个容器按配方重建 Provider：容器一 #2、再取一次 #2，容器二 #3
alias	两个限定符绑定同一个装饰器：@utc #4、@default #4；另一个装饰器 @local #5
race.naive	两个线程同时第一次取值，不加锁：创建了 2 次，两个线程拿到不同的对象
race.safe	加锁：创建了 1 次，两个线程拿到同一个对象
null	工厂合法地返回 null，取 3 次：用 instance==null 判断时工厂被调用 3 次；用独立的 created 标志时 1 次
failure	第一次创建抛异常（失败：配置未就绪），第二次取值得到 ready，工厂共被调用 2 次（失败没有被缓存）
deps.opaque	装饰器不转发依赖元数据，检查 a ↔ b 的环：通过
deps.forward	装饰器转发依赖元数据：循环依赖：a → b → a
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
