#!/usr/bin/env bash
# 锁对象的身份：实例锁与类锁、混用两种锁、每次新建或被重新赋值的锁对象、拿字符串和包装类型当锁、不加锁的读、组合操作
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC  src/LockLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
baseline.no_lock	不加锁：丢了更新（结果小于 400000）
baseline.same_lock	两个线程锁同一个对象：5 轮都是 400000
instance.count	两个实例各自的 synchronized 方法修改同一个静态变量：丢了更新（结果小于 400000）
instance.independent	一个线程在实例 1 的同步方法里：另一个线程照样进入（两把锁）
instance.static_fix	改成 static synchronized（锁的是类对象）：5 轮都是 400000
mixed_locks	一个方法用 synchronized、另一个方法用 ReentrantLock 保护同一个字段：丢了更新（结果小于 400000）
new_each_time	同步块锁的是方法里刚 new 出来的对象：丢了更新（结果小于 400000）
boxed.count	synchronized (boxed) { boxed++; }：丢了更新（结果小于 400000）
boxed.identity	Integer 自增之后，变量指向的还是原来那个对象 = false
boxed.javac	javac 对这种写法的警告：WARNING attempt to synchronize on an instance of a value-based class
key.string_runtime	两个运行时拼出来的 "order-42"（equals 为 true，== 为 false）：另一个线程照样进入（两把锁）
key.string_interned	对它们各自调用 intern() 之后：另一个线程被挡住（同一把锁）
key.string_literal	两个互不相干的类各自用字面量 "LOCK" 当锁：另一个线程被挡住（同一把锁）
key.integer_100	Integer.valueOf(100) 取两次：另一个线程被挡住（同一把锁）
key.integer_1000	Integer.valueOf(1000) 取两次：另一个线程照样进入（两把锁）
key.long_id	Long.valueOf(20261010L) 取两次（常见的「按用户 ID 加锁」）：另一个线程照样进入（两把锁）
key.lock_table	按键从 ConcurrentHashMap 里 computeIfAbsent 取锁对象：另一个线程被挡住（同一把锁）
key.lock_table_other	同一张表里另一个键 "order-43"：另一个线程照样进入（两把锁）
reader.unlocked	写方在锁内先改 a 再改 b，读方不加锁，在两次修改之间读到 a + b = 1（约定恒为 0）
reader.locked	读方也加同一把锁：读到 a + b = 0
compound.check_then_act	synchronizedList 上两个线程各自「为空才添加」，都先检查完再添加：列表里有 2 个元素
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
