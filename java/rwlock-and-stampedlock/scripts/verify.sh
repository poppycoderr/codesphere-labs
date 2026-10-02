#!/usr/bin/env bash
# 读写锁与 StampedLock 的边界：升级与降级、重入、乐观读校验、锁转换后的 stamp、全局写锁里的慢加载与按键加载
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/RwLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 场景由顺序执行或闩锁排定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4
rw.upgrade	ReentrantReadWriteLock 持有读锁时 tryLock 写锁：false（改用阻塞的 lock() 会永远等下去）
rw.downgrade	持有写锁时 tryLock 读锁：true；释放写锁后仍持有读锁 1 次
reentrant.rw	ReentrantReadWriteLock 同一线程第二次获取写锁：true，持有 2 次
reentrant.stamped	StampedLock 同一线程持有写锁时再 tryWriteLock 返回 0，tryReadLock 返回 0（0 表示失败；改用阻塞方法会自己等自己）
optimistic.invalid	乐观读期间发生写入：读到 x=60 y=70（和为 130），validate=false
optimistic.fallback	退回读锁重读：x=30 y=70（和为 100）
optimistic.valid	乐观读期间没有写入：validate=true，全程没有获取任何锁（isReadLocked=false）
convert.stamp	只有一个读者时 tryConvertToWriteLock 成功=true；之后用旧的读 stamp 解锁：抛出 IllegalMonitorStateException；isWriteLocked=true
convert.new_stamp	用转换返回的新 stamp 解锁后：isWriteLocked=false
convert.two_readers	有两个读者时 tryConvertToWriteLock 返回 0（0 表示失败，原来的读锁仍然持有）
cache.global_write_lock	键 A 在全局写锁里加载时，读已经在缓存里的键 B：100ms 内拿到读锁=false
cache.per_key	ConcurrentHashMap.computeIfAbsent 加载键 A 期间读键 B：得到 b，没有被阻塞
cache.load_once	两个线程同时请求键 A：加载函数执行了 1 次，结果 a
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
