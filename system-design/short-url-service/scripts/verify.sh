#!/usr/bin/env bash
# 短链服务：短码生成方式的可枚举性与碰撞、并发创建同一个长 URL、301/302/307/308 跳转后的请求方法、目标地址校验
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器与 JDK 21 容器（含 curl）；约 1 分钟（不含拉取镜像）；不访问外网目标
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PROJECT=csl-short-url
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
mysql_exec "$PROJECT" labs <schema/01-schema.sql
DRIVER=$(maven_jar "$MYSQL_JDBC_JAR_COORD")
java_in_network "$CID" -Xmx1g -cp "/cache/m2/$(basename "$DRIVER")" src/ShortUrl.java 2>&1 | grep -v '^WARN\|^Loading class' >"$OUT/output.tsv"
docker run --rm "$JDK_IMAGE" sh -c 'curl --version | head -1' >"$OUT/curl-version.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $MYSQL_JDBC_JAR_COORD" "jdk_image: $JDK_IMAGE"
f="$OUT/output.tsv"
cat "$f" >&2
expect_regex "$f" "^gen\.sequential	自增 ID 转 Base62，连续 5 个：\[15FTGg, 15FTGh, 15FTGi, 15FTGj, 15FTGk\]$" "自增 ID 生成的短码连续可枚举"
expect_regex "$f" "^same_url\.check_then_insert	20 个并发请求创建同一个长 URL：表里 ([2-9]|1[0-9]|20) 行" "先查再写：同一个长 URL 生成了多行"
expect_line "$f" "same_url.unique_constraint	20 个并发请求创建同一个长 URL：表里 1 行，调用方拿到 1 个不同的短码" "唯一约束：只有一行，所有调用方拿到同一个短码"
expect_line "$f" "validate	https://example.com/a?b=1 → 放行" "正常地址放行"
expect_line "$f" "validate	javascript:alert(1) → 拒绝：协议 javascript" "拒绝 javascript 协议"
expect_line "$f" "validate	http://169.254.169.254/latest/meta-data/ → 拒绝：解析到 169.254.169.254" "拒绝元数据地址"
expect_line "$f" "validate	http://internal.example/ → 拒绝：解析到 10.1.2.3" "按解析结果拒绝内网地址"
expect_line "$f" "validate	http://example.com@127.0.0.1/ → 拒绝：包含 userinfo" "拒绝 userinfo 写法"
expect_line "$f" "validate	http://localhost/admin → 拒绝：解析到 127.0.0.1" "拒绝 localhost"
expect_line "$f" "validate	http://2130706433/ → 拒绝：解析到 127.0.0.1" "十进制写法的 IP 被识别为 127.0.0.1"
expect_line "$f" "validate	http://0x7f000001/ → 拒绝：UnknownHostException" "十六进制写法 Java 不认，按解析失败拒绝"
expect_regex "$f" "^redirect\.jdk\.30[12]	.*目标收到：GET 0$" "301、302：JDK HttpClient 把 POST 改成 GET"
expect_regex "$f" "^redirect\.curl\.30[12]	.*目标收到：GET 0$" "301、302：curl 同样把 POST 改成 GET"
expect_regex "$f" "^redirect\.curl\.30[78]	.*目标收到：POST 3$" "307、308：curl 保留 POST"
expect_regex "$f" "^redirect\.jdk\.307	.*目标收到：POST 3$" "307：JDK HttpClient 保留 POST 与请求体"
expect_regex "$f" "^redirect\.jdk\.308	.*目标收到：POST 3$" "308：JDK HttpClient 保留 POST 与请求体"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
h = {int(n): int(c.replace(",", "")) for n, c in re.findall(r"^gen\.hash32\.(\d+)\t.*碰撞 ([\d,]+) 个", t, re.M)}
assert h[1_000_000] > 50 and h[10_000_000] > 50 * h[1_000_000] / 2, h      # 32 位截断：百万规模就有上百次碰撞，规模 ×10 碰撞约 ×100
r = {int(n): int(c) for n, c in re.findall(r"^gen\.random7\.(\d+)\t.*重复 (\d+) 个", t, re.M)}
assert r[10_000_000] < 100, r                                             # 7 位随机码：千万规模只有十几次，靠唯一索引重试即可
print(f"通过：32 位截断碰撞 {h}；7 位随机码重复 {r}")
PY
log "全部通过，输出在 $OUT（容器仍在运行，make clean 删除）"
