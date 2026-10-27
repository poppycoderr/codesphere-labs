#!/usr/bin/env bash
# 条件删除与条目身份、淘汰方向、并发加载、监听器异常、可变值；Caffeine 3.3.1 对照（JDK 25）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器与 Caffeine 3.3.1；约 15 秒（首次需要下载 jar）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
maven_jar com.github.ben-manes.caffeine:caffeine:3.3.1 >/dev/null
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$IMG" java -Duser.timezone=UTC -cp /cache/m2/caffeine-3.3.1.jar src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG" "caffeine: 3.3.1"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
expiry.by-key	第一次写入的过期任务按 key 删除：降价后刚写入的 89 元也被删了，缓存里是 null
expiry.by-entry	过期任务只删除自己登记时的条目 remove(key, entry)：缓存里仍是 Entry[value=89 元, version=2]
session.by-key	用户重连后旧连接的关闭回调按用户删除：u1 的在线连接 null（新连接还活着，却被标成离线）
session.by-entry	关闭回调只删除自己 remove(u1, channel-1)：删除=false，u1 的在线连接 channel-2
evict.direction	命中次数 首页 120、详情 30、冷门 1，容量满时删除排序后的第一个：降序排序删掉「首页」，升序删掉「冷门」
load.get-then-put	四个线程同时未命中、各自 get 后回源再 put：回源 4 次
load.compute	同一个 key 用 computeIfAbsent 加载：回源 1 次
listener.inline	put 之后同步调用监听器，监听器抛错：调用方看到失败（通知下游失败），缓存里却已经是 v2
mutable	调用方拿到缓存里的对象后改了一个字段：下一个读者看到的等级是「VIP（只用于这次活动的试算）」
caffeine.expiry	Caffeine：第一次写入 11 秒后（第二次写入 6 秒后）读取：89 元（过期按条目计算）
caffeine.load	Caffeine：四个线程同时 get(key, loader)，加载耗时 200ms：回源 1 次
caffeine.listener	Caffeine：移除监听器抛错时 invalidate 正常返回=true，条目已移除=true
caffeine.mutable	Caffeine 同样返回同一个对象：被改后的等级「VIP（试算）」
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
