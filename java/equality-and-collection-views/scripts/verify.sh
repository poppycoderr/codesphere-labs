#!/usr/bin/env bash
# 三套相等（==、equals/hashCode、compareTo）在 HashSet、TreeSet、Map 与 List 里的不同结果；集合视图与副本
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC  src/EqualityLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
bigdecimal	1.0 与 1.00：equals = false，compareTo = 0；HashSet 里有 2 个，TreeSet 里有 1 个；stripTrailingZeros 之后 HashSet 里有 1 个
boxed.identity	Integer 127 == 127：true；128 == 128：false；128 equals 128：true
boxed.map_key	Map<Long, String> 放入 1L 后，get(1) = null，get(1L) = alice；Long.valueOf(1).equals(1) = false
mutable_key	放进 HashSet 后修改参与 hashCode 的字段：contains(同一个对象) = false，remove = false，size = 1，遍历能找到 = true
no_hashcode	只重写 equals：两个相等对象 equals = true，HashSet.contains(相等的另一个对象) = false，ArrayList.contains = true
asymmetric	父类与子类：p.equals(cp) = true，cp.equals(p) = false；List.of(cp).contains(p) = true，List.of(p).contains(cp) = false
comparator	忽略大小写的比较器：TreeSet 加入 Order-1 与 ORDER-1 后是 [Order-1]；TreeMap 依次 put 后是 {Order-1=2}
record_array	record 的数组分量：两个内容相同的 record equals = false
as_list	Arrays.asList(int[]) 的 size = 1；Arrays.asList(Integer[]) 上 set(0, 99) 后原数组是 [99, 2, 3]；add(4)：抛出 UnsupportedOperationException
sub_list	对 subList(1, 3) 调用 clear() 后原列表是 [1, 4, 5]；取了 subList 之后再修改原列表，访问 subList：抛出 ConcurrentModificationException
unmodifiable	源列表追加元素后：unmodifiableList 看到 [a, b]，List.copyOf 看到 [a]；对 unmodifiableList 调用 add：抛出 UnsupportedOperationException
list_of_null	List.of(1, 2).contains(null)：抛出 NullPointerException；Arrays.asList(1, 2).contains(null)：false
stream_to_list	Stream.toList() 的结果 [a, null] 调用 add：抛出 UnsupportedOperationException；Collectors.toList() 的结果调用 add：true
to_map	Collectors.toMap 遇到重复键：抛出 IllegalStateException；遇到 null 值：抛出 NullPointerException
key_set	从 keySet() 里删除 a 之后 Map 是 {b=2}
remove_overload	列表 [10, 20, 30, 1] 调用 remove(1) 后是 [10, 30, 1]；再调用 remove(Integer.valueOf(1)) 后是 [10, 30]
shallow_copy	new ArrayList<>(源) 之后修改副本里的元素，源里的元素是 42
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
