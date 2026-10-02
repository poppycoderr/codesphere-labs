#!/usr/bin/env bash
# 队列加批量消费者的边界：批的上限、队列之外的在途量、按时间刷出、批内失败、关闭排空与毒丸
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin:25-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$J25" java src/BatchLab.java >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
expect_line "$f" "env	java.version=25.0.4" "运行在 JDK 25.0.4"
expect_line "$f" "size.unbounded	队列积压 1000 条，drainTo(batch)：共 1 批，最大一批 1000 条" "取到空为止：一批就是整个积压"
expect_line "$f" "size.capped	队列积压 1000 条，drainTo(batch, 99)：共 10 批，最大一批 100 条" "带上限：每批最多 100 条"
expect_regex "$f" "^inflight\.unbounded	.*生产者已放入 5000 条，队列里 0 条，已交给写入线程但未写完 5000 条，生产者已全部放完，没有被阻塞$" "队列有界但在途无界：5000 条全部堆在写入线程的队列里"
expect_regex "$f" "^inflight\.bounded	.*队列里 100 条，已交给写入线程但未写完 200 条，生产者被阻塞$" "在途额度 200：背压传回生产者"
expect_regex "$f" "^failure\.no_catch	.*写入 0 条，坏数据进死信 0 条，已取出但没写入的好数据 49 条，留在队列里没人处理 50 条；消费者线程存活=false；之后生产者放满队列后被阻塞（队列 100 条）$" "异常逃出循环：消费者线程退出，生产者被堵死"
expect_regex "$f" "^failure\.drop_batch	.*写入 50 条，坏数据进死信 0 条，已取出但没写入的好数据 49 条，留在队列里没人处理 0 条；消费者线程存活=true" "捕获后只记日志：同批的 49 条好数据一起丢失"
expect_regex "$f" "^failure\.per_item	.*写入 99 条，坏数据进死信 1 条，已取出但没写入的好数据 0 条，留在队列里没人处理 0 条；消费者线程存活=true" "整批失败后逐条重试：只有坏数据进死信"
expect_regex "$f" "^shutdown\.interrupt	.*写入 0 条，丢失 100 条$" "直接中断：手里的批和队列里的都丢失"
expect_regex "$f" "^shutdown\.drain	.*写入 100 条，丢失 0 条$" "停止接收后排空：不丢"
expect_line "$f" "pill.offer_full	队列已满时 offer(毒丸) 返回 false：毒丸没有进队列" "满队列上 offer 毒丸会失败"
expect_line "$f" "pill.one_for_two	2 个消费者只放 1 颗毒丸：仍在运行的消费者 1 个" "毒丸数量少于消费者：有消费者停不下来"
expect_line "$f" "pill.one_each	补上第 2 颗之后：仍在运行的消费者 0 个" "每个消费者一颗毒丸"
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
produced = int(re.search(r"^inflight\.bounded\t.*生产者已放入 (\d+) 条", t, re.M)[1])
assert 300 <= produced <= 350, produced               # 在途 200 + 队列 100 + 消费者手里最多一批 50
poll = re.search(r"^flush\.per_poll\t.*第一批 (\d+) 条，第一条等了 (\d+)ms", t, re.M)
dl = re.search(r"^flush\.deadline\t.*第一批 (\d+) 条，第一条等了 (\d+)ms", t, re.M)
assert int(poll[1]) == 10 and int(poll[2]) >= 700, poll.groups()     # 每来一条就重新计时：等所有 10 条来完再加 100ms
assert int(dl[2]) <= 150 and int(dl[1]) <= 3, dl.groups()            # 截止时间从第一条开始算
print(f"通过：重新计时第一条等了 {poll[2]}ms，按截止时间等了 {dl[2]}ms；在途受限时生产者只放入 {produced} 条")
PY
log "全部通过，输出在 $OUT"
