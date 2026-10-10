#!/usr/bin/env bash
# 序列化的版本兼容：多一个字段、少一个字段、null、新增的枚举值、读改写，Jackson 2 与 Jackson 3 默认行为的差别，Protobuf 的 unknown fields、字段编号重用、0 与没填、开放与封闭枚举
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="com.fasterxml.jackson.core:jackson-databind:2.22.3 com.fasterxml.jackson.core:jackson-core:2.22.3 com.fasterxml.jackson.core:jackson-annotations:2.22 com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.3 tools.jackson.core:jackson-databind:3.2.3 tools.jackson.core:jackson-core:3.2.3 com.google.protobuf:protobuf-java:4.36.2"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/SerdeLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 jackson2=2.22.3 jackson3=3.2.3 protobuf=4.36.2
json.unknown_field.jackson2	发送方多了一个 giftWrap 字段，Jackson 2 默认：抛出 UnrecognizedPropertyException
json.unknown_field.jackson3	同一条消息，Jackson 3 默认：OrderV1[id=A1, quantity=2]
json.missing_primitive	消息里没有 quantity（int）：Jackson 2 OrderV1[id=A1, quantity=0]；Jackson 3 OrderV1[id=A1, quantity=0]
json.null_primitive	quantity 是 null：Jackson 2 OrderV1[id=A1, quantity=0]；Jackson 3 抛出 MismatchedInputException
json.absent_vs_null.pojo	「没传 nickname」与「nickname 传了 null」读进对象之后：null 与 null，分不出来
json.absent_vs_null.tree	读成树：没传时 has("nickname") = false；传了 null 时 has = true，isNull = true
json.unknown_enum	发送方新增了枚举值 REFUNDING：Jackson 2 抛出 InvalidFormatException；Jackson 3 抛出 InvalidFormatException
json.round_trip.pojo	按旧版本的类读入、数量改成 3、再写出：{"id":"A1","quantity":3}（giftWrap 没有了）
json.round_trip.any	类上用 @JsonAnySetter / @JsonAnyGetter 收下不认识的字段：{"id":"A1","quantity":3,"giftWrap":true}
json.untyped_numbers	读成 Map 之后各个值的类型：small → Integer  big → Long  id → Long  price → Double
json.untyped_cast	(Long) map.get("small")：抛出 ClassCastException
json.instant.jackson2	Jackson 2 默认写 Instant：抛出 InvalidDefinitionException
json.instant.jackson2_module	Jackson 2 注册 JavaTimeModule 后：{"id":"e1","at":1791619200.000000000}
json.instant.jackson3	Jackson 3 默认：{"id":"e1","at":"2026-10-10T08:00:00Z"}
json.instant.cross	Jackson 3 读 Jackson 2 写的：2026-10-10T08:00:00Z；Jackson 2 读 Jackson 3 写的：2026-10-10T08:00:00Z
json.trailing	合法 JSON 后面跟着多余内容：Jackson 2 OrderV1[id=A1, quantity=1]；Jackson 3 抛出 StreamReadException
proto.unknown_field	旧版本解析新版本的消息：quantity = 2，不认识的字段编号 [3] 留在 unknown fields 里
proto.round_trip	旧版本把数量改成 3 再发出去，新版本读到：quantity = 3，gift_wrap = true
proto.reuse_same_type	编号 2 原来是 email（string），删掉后给了 nickname（string）；读旧数据：nickname = a@example.com
proto.reuse_other_type	编号 2 给了 age（int32）；读旧数据：age = 0，编号 [2] 进了 unknown fields，不报错
proto.narrowed	编号 3 从 int64 改成 int32；原值 5000000000 读成 705032704
proto.renamed	编号 2 只改名为 mail_address；读旧数据：a@example.com
proto.presence.implicit	proto3 的 int32 quantity = 2：填 0 与不填，序列化结果相同 = true（都是 4 字节），字段能否判断有没有填 = false
proto.presence.optional	改成 optional int32 quantity = 2：填 0 是 6 字节、不填是 4 字节；hasField 分别是 true 与 false
proto.unknown_enum.proto3	proto3：旧版本读到 status = UNKNOWN_ENUM_VALUE_Status_2（数值 2），unknown fields []；原样转发后新版本读到 REFUNDING
proto.unknown_enum.proto2	proto2：旧版本读到 status = CREATED（数值 0），unknown fields [2]；原样转发后新版本读到 REFUNDING
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
