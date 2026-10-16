#!/usr/bin/env bash
# 不变量的入口：构造器、JSON 反序列化、Java 序列化、反射写字段、暴露的内部集合、可变元素、父类公共 setter 与 Long 的 ==
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="tools.jackson.core:jackson-databind:3.2.3 tools.jackson.core:jackson-core:3.2.3 com.fasterxml.jackson.core:jackson-annotations:2.22"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/InvariantLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 jackson=3.2.3
entry.constructor	new Quantity(0)：被拒绝（IllegalArgumentException：数量必须在 1 到 99 之间：0）
entry.json_class	JSON {"value":0} 反序列化为带无参构造器的类：Quantity(0)
entry.json_record	同样的 JSON 反序列化为 record（经过紧凑构造器）：被拒绝（ValueInstantiationException：数量必须在 1 到 99 之间：0）
entry.java_serialization	Java 序列化的字节被改成 0 之后反序列化：Quantity(0)
entry.json_creator	改成只有一个 @JsonCreator 构造器的类，同样的 JSON：被拒绝（ValueInstantiationException：数量必须在 1 到 99 之间：0）
entry.read_object	加了 readObject 校验的类，被改过的序列化字节：被拒绝（IllegalArgumentException：数量必须在 1 到 99 之间：0）
entry.reflection	反射直接写私有字段（ORM 与映射工具常用的方式）：Quantity(0)
aggregate.method	购物车已有 3 行，再调用 add：被拒绝（IllegalStateException：最多 3 行）
aggregate.exposed_list	从 lines() 拿到内部列表直接 add：现在有 4 行
aggregate.view	改成返回 unmodifiableList 之后 add：被拒绝（UnsupportedOperationException：null）
aggregate.mutable_element	但列表里的元素本身可变，setQty(0)：购物车里现在是 [x0]
transition.method	新订单直接 ship()：被拒绝（IllegalStateException：未支付不能发货）
transition.base_setter	调用父类的公共 setStatus("SHIPPED")：状态变成 SHIPPED，没有经过支付
identity.long	用 == 比较 Long 类型的 id：127 == 127 为 true，128 == 128 为 false；equals 都为 true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
