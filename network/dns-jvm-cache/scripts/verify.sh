#!/usr/bin/env bash
# DNS 改了之后 Java 什么时候才连到新地址：JVM 的正向与负向 DNS 缓存、HttpClient 与 HttpURLConnection 的连接复用
# 自建 Docker 网络：CoreDNS（只负责 lab.test）+ 两个后端 + 客户端容器；约 3 分钟
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT" build/dns
PROJECT=csl-dns
JDK=eclipse-temurin@sha256:78ab9771b4650066c3ef748d46e05dbd6094d8bb34e0667a074486812efd655b
echo "172.29.53.11 api.lab.test" >build/dns/hosts
docker compose -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -p "$PROJECT" up -d >&2
sleep 5
client() { log "场景 $*"; docker run --rm --network "${PROJECT}_lab" --dns 172.29.53.53 -v "$PWD/src:/src:ro" -v "$PWD/build/dns:/dns" "$JDK" java /src/Dns.java "$@" 2>/dev/null | awk -F'\t' 'NF>1' >>"$OUT/facts.tsv"; }
: >"$OUT/facts.tsv"
client resolve
client resolve 5
client resolve 0
client negative
client pool 0
docker compose -p "$PROJECT" restart backend1 backend2 >/dev/null 2>&1; sleep 5
client pool-urlconnection 0
write_environment "$OUT/environment.txt" "jdk_image: $JDK" "coredns: 1.14.7（compose.yaml 固定 digest），记录 TTL 5 秒，文件每秒重新加载"
python3 - "$OUT/facts.tsv" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
def ms(key):
    m = re.search(rf"^{re.escape(key)}\t.*?(\d+) ms 后才", t, re.M)
    return int(m[1]) if m else None
def ok(c, msg):
    if not c:
        sys.exit("失败：" + msg + "\n" + t)
    print("通过：" + msg)
d, five, zero, neg = ms("resolve.default"), ms("resolve.5"), ms("resolve.0"), ms("negative")
ok(d is not None and 29000 <= d < 32000, f"默认设置：记录 TTL 5 秒，JVM 约 30 秒后才解析到新地址（{d} ms）")
ok(five is not None and 4000 <= five < 7000, f"networkaddress.cache.ttl=5：约 5 秒（{five} ms）")
ok(zero is not None and zero < 2500, f"networkaddress.cache.ttl=0：只剩 DNS 服务器自己的更新延迟（{zero} ms）")
ok(neg is not None and 9000 <= neg < 12000, f"查不到的结果默认缓存约 10 秒（{neg} ms）")
def bucket(key):
    m = re.search(rf"^{re.escape(key)}\t[^{{]*\{{([^}}]*)\}}", t, re.M)
    return dict((k, int(v)) for k, v in re.findall(r"(backend-\d|error)=(\d+)", m[1]))
s = bucket("pool.switched")
ok(s.get("backend-2", 0) > 20 and s.get("backend-1", 0) <= 2, f"HttpClient：JVM 解析到新地址后，新请求立刻落到 backend-2（{s}）")
u = bucket("pool-urlconnection.switched")
ok(u.get("backend-1", 0) > 20 and u.get("backend-2", 0) == 0, f"HttpURLConnection：解析已经是新地址，请求仍然全部落在旧的 backend-1（{u}）")
v = bucket("pool-urlconnection.drained")
ok(v.get("backend-2", 0) > 20 and v.get("backend-1", 0) <= 2, f"旧后端返回 Connection: close 之后，HttpURLConnection 才切到 backend-2（{v}）")
PY
log "全部通过，输出在 $OUT（环境仍在运行，make clean 删除）"
