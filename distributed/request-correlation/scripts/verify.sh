#!/usr/bin/env bash
# 请求编号、待完成表、超时清理、编号复用、连接断开与写出完成（JDK 25，可控内存传输）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$IMG" java -Duser.timezone=UTC src/Lab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
shared	两个请求共用一个完成对象，B 的响应先到：A 拿到「B 的余额 300」，B 拿到「B 的余额 300」
pending	按请求编号登记独立的完成对象，响应乱序到达：A 拿到「A 的余额 100」，B 拿到「B 的余额 300」
register.after-send	先发送再登记，响应在登记前到达：调用方 等待中，被当成未知编号丢弃的响应 1 个，表中残留 1 项
register.before-send	先登记再发送：调用方拿到「pong」，表中残留 0 项
timeout.leak	1000 个请求都超时：超时时不删表项，表中残留 1000 项；删除表项，残留 0 项
timeout.reuse	编号只有 4 个、按 0..3 循环：订单 1 超时后，复用编号 0 的订单 5 收到了「订单 1：已发货」；订单 1 自己是 失败（超时）
timeout.unique	编号在连接生命周期内不复用：订单 1 的迟到响应被当成未知编号丢弃（1 个），订单 5 仍在等待中，订单 1 是 失败（超时）
close.naive	连接断开但没有通知待完成表：3 个请求都在等待中，要等各自超时
close.fail-all	连接断开时把表里的请求全部失败：失败（连接已断开），表中残留 0 项
write.vs-reply	写出 Future 成功=true，对端进程在处理前崩溃，业务结果：失败（超时）（扣减可能做了也可能没做）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
