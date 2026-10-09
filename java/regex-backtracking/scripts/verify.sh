#!/usr/bin/env bash
# 正则回溯：记录 charAt 调用次数，比较各表达式在「差一点就匹配」的输入上随长度的增长；JDK 25 与 JDK 8 各跑一遍
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 与 temurin 8 容器；约 1 分钟
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
J8="eclipse-temurin@sha256:3a280002b0d1212a62e9f5095c10b07ef3b62c1b13916fe093438e7e9af44b8b"
run() { docker run --rm -v "$PWD:/w:ro" -w /w "$1" sh -c 'mkdir -p /tmp/o && javac -encoding UTF-8 -d /tmp/o src/RegexLab.java 2>/dev/null; java -Dfile.encoding=UTF-8 -cp /tmp/o RegexLab'; }
run "$J25" >"$OUT/output-jdk25.tsv"
run "$J8" >"$OUT/output-jdk8.tsv"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "jdk8_image: $J8"
cat "$OUT/output-jdk25.tsv" >&2
# charAt 的调用次数由表达式、输入与 JDK 的实现决定，同一个镜像上是确定的：与预期逐行比较
diff - "$OUT/output-jdk25.tsv" <<'EXPECTED25' || fail "JDK 25 的输出与预期不一致"
env	java.version=25.0.4.1
nested.ok	(a+)+b	n=10: 匹配 13	n=14: 匹配 17	n=18: 匹配 21	n=22: 匹配 25	n=26: 匹配 29
nested.fail	(a+)+b	n=10: 不匹配 121	n=14: 不匹配 225	n=18: 不匹配 361	n=22: 不匹配 529	n=26: 不匹配 729
word_list.fail	^(\w+\s?)+$	n=10: 不匹配 140	n=14: 不匹配 252	n=18: 不匹配 396	n=22: 不匹配 572	n=26: 不匹配 780
csv_digits.fail	^(\d+,?)+$	n=10: 不匹配 140	n=14: 不匹配 252	n=18: 不匹配 396	n=22: 不匹配 572	n=26: 不匹配 780
backref.fail	(["'])(\w+\s?)+\1	n=10: 不匹配 5117	n=14: 不匹配 81917	n=18: 不匹配 1310717	n=22: 不匹配 20971517	n=26: 超过 200000000
lazy_outer.fail	(a+)+?b	n=10: 不匹配 3070	n=14: 不匹配 49150	n=18: 不匹配 786430	n=22: 不匹配 12582910	n=26: 超过 200000000
three_levels.fail	((a+)+)+b	n=10: 不匹配 6119	n=14: 不匹配 98271	n=18: 不匹配 1572823	n=22: 不匹配 25165775	n=26: 超过 200000000
bounded.fail	(a{1,10}){1,10}b	n=10: 不匹配 3068	n=14: 不匹配 46627	n=18: 不匹配 550332	n=22: 不匹配 4510014	n=26: 不匹配 26293876
fixed.simple	a+b	n=10: 不匹配 21	n=14: 不匹配 29	n=18: 不匹配 37	n=22: 不匹配 45	n=26: 不匹配 53
fixed.possessive	(a++)+b	n=10: 不匹配 13	n=14: 不匹配 17	n=18: 不匹配 21	n=22: 不匹配 25	n=26: 不匹配 29
fixed.atomic	(?>a+)+b	n=10: 不匹配 13	n=14: 不匹配 17	n=18: 不匹配 21	n=22: 不匹配 25	n=26: 不匹配 29
fixed.word_list	^\w+(\s\w+)*$	n=10: 不匹配 23	n=14: 不匹配 31	n=18: 不匹配 39	n=22: 不匹配 47	n=26: 不匹配 55
fixed.csv_digits	^\d+(,\d+)*,?$	n=10: 不匹配 33	n=14: 不匹配 45	n=18: 不匹配 57	n=22: 不匹配 69	n=26: 不匹配 81
fixed.backref	(["'])\w+(\s\w+)*\s?\1	n=10: 不匹配 52	n=14: 不匹配 72	n=18: 不匹配 92	n=22: 不匹配 112	n=26: 不匹配 132
trailing_ws.fail	\s+$	n=1000: 不匹配 503500	n=2000: 不匹配 2007000	n=4000: 不匹配 8014000	n=8000: 不匹配 32028000
trailing_ws.ok	\s+$	n=1000: 匹配 1001	n=2000: 匹配 2001	n=4000: 匹配 4001	n=8000: 匹配 8001
budget	((a+)+)+b 匹配 40 个 a 加 !，预算 1000000 次：抛出 IllegalStateException（regex budget exceeded），已调用 1000000 次
length_limit	((a+)+)+b 匹配 12 个 a 加 !：不匹配 24547
EXPECTED25
diff - "$OUT/output-jdk8.tsv" <<'EXPECTED8' || fail "JDK 8 的输出与预期不一致"
env	java.version=1.8.0_504
nested.ok	(a+)+b	n=10: 匹配 13	n=14: 匹配 17	n=18: 匹配 21	n=22: 匹配 25	n=26: 匹配 29
nested.fail	(a+)+b	n=10: 不匹配 3070	n=14: 不匹配 49150	n=18: 不匹配 786430	n=22: 不匹配 12582910	n=26: 超过 200000000
word_list.fail	^(\w+\s?)+$	n=10: 不匹配 3838	n=14: 不匹配 61438	n=18: 不匹配 983038	n=22: 不匹配 15728638	n=26: 超过 200000000
csv_digits.fail	^(\d+,?)+$	n=10: 不匹配 3838	n=14: 不匹配 61438	n=18: 不匹配 983038	n=22: 不匹配 15728638	n=26: 超过 200000000
backref.fail	(["'])(\w+\s?)+\1	n=10: 不匹配 5117	n=14: 不匹配 81917	n=18: 不匹配 1310717	n=22: 不匹配 20971517	n=26: 超过 200000000
lazy_outer.fail	(a+)+?b	n=10: 不匹配 3070	n=14: 不匹配 49150	n=18: 不匹配 786430	n=22: 不匹配 12582910	n=26: 超过 200000000
three_levels.fail	((a+)+)+b	n=10: 不匹配 118097	n=14: 不匹配 9565937	n=18: 超过 200000000	n=22: 超过 200000000	n=26: 超过 200000000
bounded.fail	(a{1,10}){1,10}b	n=10: 不匹配 3068	n=14: 不匹配 46627	n=18: 不匹配 550332	n=22: 不匹配 4510014	n=26: 不匹配 26293876
fixed.simple	a+b	n=10: 不匹配 21	n=14: 不匹配 29	n=18: 不匹配 37	n=22: 不匹配 45	n=26: 不匹配 53
fixed.possessive	(a++)+b	n=10: 不匹配 13	n=14: 不匹配 17	n=18: 不匹配 21	n=22: 不匹配 25	n=26: 不匹配 29
fixed.atomic	(?>a+)+b	n=10: 不匹配 13	n=14: 不匹配 17	n=18: 不匹配 21	n=22: 不匹配 25	n=26: 不匹配 29
fixed.word_list	^\w+(\s\w+)*$	n=10: 不匹配 23	n=14: 不匹配 31	n=18: 不匹配 39	n=22: 不匹配 47	n=26: 不匹配 55
fixed.csv_digits	^\d+(,\d+)*,?$	n=10: 不匹配 33	n=14: 不匹配 45	n=18: 不匹配 57	n=22: 不匹配 69	n=26: 不匹配 81
fixed.backref	(["'])\w+(\s\w+)*\s?\1	n=10: 不匹配 52	n=14: 不匹配 72	n=18: 不匹配 92	n=22: 不匹配 112	n=26: 不匹配 132
trailing_ws.fail	\s+$	n=1000: 不匹配 503500	n=2000: 不匹配 2007000	n=4000: 不匹配 8014000	n=8000: 不匹配 32028000
trailing_ws.ok	\s+$	n=1000: 匹配 1001	n=2000: 匹配 2001	n=4000: 匹配 4001	n=8000: 匹配 8001
budget	((a+)+)+b 匹配 40 个 a 加 !，预算 1000000 次：抛出 IllegalStateException（regex budget exceeded），已调用 1000000 次
length_limit	((a+)+)+b 匹配 12 个 a 加 !：不匹配 1062881
EXPECTED8
log "全部通过：两个 JDK 的输出都与预期逐行一致，输出在 $OUT"
