#!/usr/bin/env bash
# URI 模板编译成正则：字面字符转义、变量正则里的捕获组、替换串转义、模板优先级与比较器契约（JDK 25、Jersey 4.0.3 UriTemplate）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 容器下载依赖，temurin 25 容器运行；约 30 秒（首次需下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
rm -rf build/lib; mkdir -p build/lib
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/m2repo:/root/.m2" -w /w "$MVN" mvn -q -B dependency:copy-dependencies -DoutputDirectory=/w/build/lib >/dev/null 2>&1 || fail "依赖下载失败"
ls build/lib | sort >"$OUT/classpath.txt"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" sh -c 'java -Duser.timezone=UTC -cp "$(ls build/lib/*.jar | tr "\n" ":")" src/Lab.java' 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "jersey: 4.0.3"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java=25.0.4.1+1-LTS jersey=4.0.3
literal.naive	/files/{name}.txt 朴素编译后匹配 /files/reportXtxt：name=report
literal.quoted	字面部分 Pattern.quote 后匹配 /files/reportXtxt：不匹配；匹配 /files/report.txt：name=report
literal.jersey	Jersey UriTemplate 匹配 /files/reportXtxt：不匹配；匹配 /files/report.txt：name=report
groups.naive	/orders/{id: (a|b)\d+}/{part} 朴素按出现顺序取组，匹配 /orders/a12/items：id=a12 part=a
groups.named	改用命名组：id=a12 part=items
groups.jersey	Jersey UriTemplate：id=a12 part=items
replace.dollar	用 replaceAll 把 {v} 换成 US$5：IndexOutOfBoundsException：No group 5
replace.quoted	替换串先 Matcher.quoteReplacement：price=US$5
replace.backslash	替换值是 C:\tmp（字符串里一个反斜杠）：replaceAll 得到「path=C:tmp」，quoteReplacement 后得到「path=C:\tmp」
order.jersey	按 Jersey UriTemplate.COMPARATOR 排序：[/users/me, /users/{id}/{tab}, /users/{id: \d+}, /users/{id}, /{any: .*}]
match._users_me	/users/me 由 /users/me 处理
match._users_42	/users/42 由 /users/{id: \d+} 处理
match._users_abc	/users/abc 由 /users/{id} 处理
match._users_42_posts	/users/42/posts 由 /users/{id}/{tab} 处理
match._static_a.css	/static/a.css 由 /{any: .*} 处理
order.length	按模板字符串长度降序排序：[/users/{id}/{tab}, /users/{id: \d+}, /users/{id}, /{any: .*}, /users/me]；/users/me 由 /users/{id} 处理
contract	检查 sgn(compare(a,b)) == -sgn(compare(b,a))：「长度大于等于就返回 -1」的比较器在 25 对里违反 5 对（都是元素与自身比较：compare(a,a) 返回 -1 而不是 0），Jersey 的比较器违反 0 对
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
