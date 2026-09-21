#!/usr/bin/env bash
# 公平锁与非公平锁的吞吐、可重入计数、条件队列与同步队列、公平锁上 tryLock() 插队
# 约 20 秒，会占满 8 个线程；吞吐数字只用于同一次运行内的相对比较
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
OUT="${1:-build/run}"; mkdir -p "$OUT"
require_java 21
java src/Aqs.java >"$OUT/aqs-output.txt"
write_environment "$OUT/environment.txt"
f="$OUT/aqs-output.txt"
expect_line "$f" "释放 2 次后 getHoldCount() = 1，isLocked() = true" "可重入计数"
expect_line "$f" "多释放一次：IllegalMonitorStateException" "多释放一次抛异常"
expect_line "$f" "3 个线程 await 之后：条件队列 3，同步队列 0" "await 后在条件队列"
expect_line "$f" "signalAll 之后、unlock 之前：条件队列 0，同步队列 3，waiter 状态 WAITING" "signalAll 只是移到同步队列"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
tp = lambda name: int(re.search(r"^\s+" + name + r"\s+([\d,]+) 次/秒", t, re.M).group(1).replace(",", ""))
nonfair, fair = tp("非公平 ReentrantLock"), tp("公平 ReentrantLock")
assert fair * 20 < nonfair, f"公平锁吞吐应比非公平锁低一个数量级以上：{fair} vs {nonfair}"
print(f"通过：公平锁吞吐是非公平锁的 1/{nonfair // fair}")
m = re.findall(r"抢到锁 (\d+) / 200 次，其中排队线程仍在队列中（插队） (\d+) 次", t)
(ok, barged), (ok_t, barged_t) = [(int(x), int(y)) for x, y in m]
assert barged > 0, f"公平锁上 tryLock() 应能插到排队线程前面：{barged}"
assert barged_t == 0, f"tryLock(0, SECONDS) 不应在有线程排队时成功：{barged_t}"
print(f"通过：tryLock() 插队 {barged}/200（成功 {ok}），tryLock(0, SECONDS) 插队 {barged_t}/200（成功 {ok_t}，都发生在队列已空之后）")
PY
