#!/usr/bin/env bash
# 局部状态与集合的契约：几种列表的可变性与 null、视图与快照、final 与 record 的浅不可变、确定赋值检查、var 与菱形、Optional
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
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/LocalStateLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
list.add	add 一个元素：ArrayList true；List.of 抛出 UnsupportedOperationException；Arrays.asList 抛出 UnsupportedOperationException；Stream.toList() 抛出 UnsupportedOperationException；Collectors.toList() true
list.set	set(0, x)：Arrays.asList a；List.of 抛出 UnsupportedOperationException
list.null_element	放入 null：ArrayList 2；List.of("a", null) 抛出 NullPointerException；Stream.of("a", null).toList() [a, null]；List.copyOf(含 null 的列表) 抛出 NullPointerException
list.contains_null	查询 contains(null)：ArrayList false；List.of 抛出 NullPointerException
list.view_vs_copy	源列表加入 b 之后：unmodifiableList 视图看到 [a, b]，List.copyOf 快照仍是 [a]
final.reference	final List 变量照样能 add：[x]
final.record	record 的字段是 final 的，构造后修改传入的列表：record 里看到 [apple, pear]
definite.ok	final int fee; if/else 两个分支各赋一次值：编译通过
definite.missing	只在 if 分支赋值：编译失败（variable fee might not have been initialized）
definite.placeholder	先给占位值 0 再按条件改：编译通过（漏掉的分支返回 0，编译器不再提醒）
var.diamond	var list = new ArrayList<>(); 推断出的类型：ArrayList<Object>：放进 1 和 "x" 都能编译，取出时 incompatible types: java.lang.Object cannot be converted to java.lang.String
optional.get	Optional.empty().get()：抛出 NoSuchElementException；Optional.of(null)：抛出 NullPointerException；Optional.ofNullable(null).orElse("默认")：默认
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
