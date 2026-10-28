#!/usr/bin/env bash
# Netty ByteBuf 引用计数：泄漏检测、管道尾部释放、自动释放与转交、派生视图、拒绝分支、写失败、出站起点（JDK 25 + Netty 4.2.19.Final）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，Netty 4.2.19.Final 的 jar 从 Maven Central 下载到 $LABS_CACHE；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
CP=""; while read -r c; do CP="$CP:/cache/m2/$(basename "$(maven_jar "$c")")"; done < deps.txt
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$IMG" java -cp "${CP#:}" src/Lab.java 2>"${OUT:-/tmp}/leak-report.txt" >"$f"
write_environment "$OUT/environment.txt" "image: $IMG" "netty_jars: $(tr '\n' ' ' < deps.txt)"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
leak.refcnt	吞掉之后 refCnt=1
leak.swallow	对象被回收后 PARANOID 级别报告泄漏 1 条；最近一条访问记录是管道写入的提示「SwallowHandler 从这里开始处理这条消息」=true；还记录了创建位置=true
tail.release	真实通道（Local 传输）里处理器只调用 fireChannelRead、后面没有人处理：返回后 refCnt=0
tail.embedded	同样的处理器放进 EmbeddedChannel：refCnt=1，消息进了 readInbound() 的队列=true（测试里读出来之后要自己释放）
simple.handoff	SimpleChannelInboundHandler 把消息放进队列、没有 retain：channelRead0 返回后 refCnt=0，队列消费者读取时 IllegalReferenceCountException
simple.retain	转交前 retain：channelRead0 返回后 refCnt=1，消费者读到 job-2，用完 release 后 refCnt=0
derived.shared	slice 与 duplicate 和原缓冲区共享计数（slice.refCnt=1，duplicate.refCnt=1）：释放 slice 之后原缓冲区 refCnt=0
derived.retained	retainedSlice 让原缓冲区 refCnt 变为 2；原持有者释放后切片仍可读（0123），切片释放后原缓冲区 refCnt=0
reject.close	消息不合法时直接 ctx.close() 返回：连接已关闭=true，消息 refCnt=1（没有人会再释放它）
reject.fixed	关闭前先 ReferenceCountUtil.release：消息 refCnt=0
write.closed	向已关闭的通道写：future 成功=false（StacklessClosedChannelException），消息 refCnt=0；调用方再 release 一次：IllegalReferenceCountException
pipeline.order	管道 [OutA, 入站处理器, OutB]：入站处理器里 ctx.write 经过 [OutA]，ctx.channel().write 经过 [OutB, OutA]
leak.after	其余场景都按约定释放后，再次触发检测：泄漏报告 0 条
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
