#!/usr/bin/env bash
# SQL 注入的边界：值的位置拼接与绑定、排序列绑定不了与布尔盲注、LIKE 的通配符、IN 的单个占位符、allowMultiQueries、二次注入
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：共享的 MySQL 8.4.11 容器（2 CPU、1 GB）与 JDK 25 容器；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
JDK_IMAGE="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
JDBC="com.mysql:mysql-connector-j:9.7.0"
PROJECT=csl-sql-injection-boundaries
mysql_down "$PROJECT" >/dev/null 2>&1 || true
CID=$(mysql_up "$PROJECT")
DRIVER=$(maven_jar "$JDBC")
f="$OUT/output.tsv"
java_in_network "$CID" -cp "/cache/m2/$(basename "$DRIVER")" src/InjectionLab.java 2>/dev/null >"$f"
{ echo "image: $(docker inspect -f '{{.Config.Image}}' "$CID")"; echo "jdbc: $JDBC"; echo "jdk_image: $JDK_IMAGE"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "mysql: 8.4.11（shared/docker/mysql84，固定 digest）" "jdbc: $JDBC"
cat "$f" >&2
# 语句按固定顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 mysql=8.4.11 driver=mysql-connector-j-9.7.0
value.concat	owner 传入 alice' OR '1'='1，拼进 SQL：返回 5 行（alice 只有 2 行）
value.bound	同样的输入，绑定参数：返回 0 行
value.numeric	id 传入 1 OR 1=1，数值不加引号直接拼接：返回 5 行（输入里没有任何引号）
value.union	id 传入 0 UNION SELECT api_token FROM accounts，拼接后读到别的表：[tok_a1, tok_k7]
order.bound	ORDER BY ? 绑定 "price"：返回顺序 [1, 2, 3, 4, 5]（按价格应为 [4, 1, 5, 3, 2]）
order.bound_desc	ORDER BY price ? 绑定 "DESC"：抛出 SQLSyntaxErrorException
order.concat	把排序列拼进 SQL，传入 price：[4, 1, 5, 3, 2]
order.blind	排序参数里放一个 CASE 子查询，只看返回的第一行是谁，逐位猜 admin 的 api_token：读出 tok_k7
order.allowlist	排序参数先查白名单再拼接：传入 newest → [5, 4, 3, 2, 1]；传入那个 CASE 子查询 → 不在白名单里，拒绝
like.percent	搜索框输入 %，LIKE CONCAT('%', ?, '%')：返回 5 行（名字里真有百分号的只有 1 个）
like.underscore	输入 a_b：返回 [a_b tester, axb adapter]
like.escaped	把输入里的 \、%、_ 转义后再绑定：[a_b tester]
in.one_placeholder	IN (?) 绑定字符串 "1,2,3"：返回 id [1]（想要的是 [1, 2, 3]）
in.placeholders	IN (?, ?, ?) 逐个绑定：返回 id [1, 2, 3]
stacked.default	id 传入 1; UPDATE accounts SET role='admin' …，默认连接参数下拼接执行：抛出 SQLSyntaxErrorException；alice 的角色 [user]
stacked.multi	连接串加了 allowMultiQueries=true 之后同样的输入：alice 的角色 [admin]
second_order	用绑定参数存入 owner = x' OR '1'='1；另一处代码把它从库里读出来拼进 SQL：返回 6 行（这个 owner 名下只有 1 行）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
