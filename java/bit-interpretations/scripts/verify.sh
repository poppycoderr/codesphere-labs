#!/usr/bin/env bash
# 同一串位的几种解释：补码与无符号、扩展、进位与溢出、无符号运算、相减比较、缩窄（JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$IMG" java -Duser.timezone=UTC src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
byte.ff	(byte) 0xFF = -1；Byte.toUnsignedInt = 255；ff == 0xFF：false
sum.bits	字节 80 FF 10 01：按有符号累加 -112，按无符号累加 400；两者低 8 位 0x90 / 0x90；signedSum % 256 = -112，Math.floorMod = 144
u16.parse	大端字节 01 F0 拼成 16 位无符号数：(hi << 8) | lo = -16（0xFFFFFFF0），((hi & 0xFF) << 8) | (lo & 0xFF) = 496
u16.buffer	ByteBuffer.getShort(FF FE) = -2，Short.toUnsignedInt = 65534；char 是无符号的：(int) (char) s = 65534
shift.byte	取 0xF0 的高 4 位：(byte) (b >>> 4) = -1，(b & 0xFF) >>> 4 = 15；b >> 4 = -1
shift.int	-16 >> 2 = -4，-16 >>> 2 = 1073741820；1 << 32 = 1，1L << 64 = 1（移位数只取低 5/6 位）
endian	字节 01 00 00 00：默认（大端）getInt = 16777216，小端 getInt = 1
flag.7f01	0x7F + 0x01 = 0x80（无符号 127 + 1 → 128，有符号 127 + 1 → -128）进位 0，溢出 1
flag.ff01	0xFF + 0x01 = 0x00（无符号 255 + 1 → 0，有符号 -1 + 1 → 0）进位 1，溢出 0
flag.8080	0x80 + 0x80 = 0x00（无符号 128 + 128 → 0，有符号 -128 + -128 → 0）进位 1，溢出 1
flag.0101	0x01 + 0x01 = 0x02（无符号 1 + 1 → 2，有符号 1 + 1 → 2）进位 0，溢出 0
int.wrap	Integer.MAX_VALUE + 1 = -2147483648；Math.addExact：ArithmeticException：integer overflow
int.carry	无符号 4294967280 + 32：结果 16，Integer.compareUnsigned(sum, a) < 0 判断出进位：true；Math.addExact 不报错（有符号 -16 + 32 没有溢出）：无异常
neg.min	-Integer.MIN_VALUE = -2147483648；Integer.MIN_VALUE / -1 = -2147483648；Math.negateExact：ArithmeticException：integer overflow
cmp.unsigned	0xFFFFFFFF 与 1：有符号比较 -1，Integer.compareUnsigned 1
div.unsigned	0xFFFFFFFF / 2：有符号 0，Integer.divideUnsigned 2147483647；% 10：有符号 -1，remainderUnsigned 5
str.unsigned	打印：-1 / toUnsignedString 4294967295 / toHexString ffffffff；Long 最大无符号值打印为 18446744073709551615
parse.hex	Integer.parseInt("FFFFFFFF", 16)：NumberFormatException：For input string: "FFFFFFFF" under radix 16；parseUnsignedInt = -1；(int) Long.parseLong = -1
cmp.subtract	比较器 (a, b) -> a - b：compare(MIN_VALUE, 1) = 2147483647（判为 MIN_VALUE 更大）；排序结果 [0, 5, 2147483647, -2147483648, -1]；Integer::compare [-2147483648, -1, 0, 5, 2147483647]
cmp.wrap	计时器从 Long.MAX_VALUE-5 走了 10：t1 > t0 为 false，t1 - t0 > 0 为 true，经过 10
seq.wrap	序号越过 MAX_VALUE，B 比 A 晚 201：B - A = 201，判为更晚；C 比 A 晚 2^31 + 1：C - A = -2147483647，判为更早
narrow	(int) 3000000000L = -1294967296；Math.toIntExact：ArithmeticException：integer overflow；(byte) 200 = -56；(short) 70000 = 4464
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
