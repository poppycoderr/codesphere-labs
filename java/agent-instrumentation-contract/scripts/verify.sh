#!/usr/bin/env bash
# Java agent 插桩契约：advice 异常隔离、异常出口、构造器、retransform 与还原、重复转换、参数采集、上报队列（JDK 25 + ByteBuddy 1.18.16）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，ByteBuddy 1.18.16 的 jar 从 Maven Central 下载到 $LABS_CACHE；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
BB=$(basename "$(maven_jar net.bytebuddy:byte-buddy:1.18.16)")
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$IMG" ./run.sh "/cache/m2/$BB" 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "image: $IMG" "byte_buddy: 1.18.16"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
advice.throws	进入通知抛异常、没有 suppress：业务方法 total(3) 抛出 IllegalStateException：advice bug
advice.suppress	同样的通知加 suppress = Throwable.class：total(3) 返回 300
exit.default	出口通知默认写法：refund(10) 返回 10，refund(-1) 抛出 IllegalArgumentException：negative amount；进入 2 次、出口 1 次
exit.onThrowable	onThrowable = Throwable.class：refund(-1) 抛出 IllegalArgumentException：negative amount；进入 1 次、出口 1 次，通知看到的异常 IllegalArgumentException，原异常照常抛给调用方
return.primitive	用 @Return(typing = DYNAMIC) Object 读返回值：long 方法得到 return=42（Long）；void 方法得到 return=null（null）
ctor.enter	构造器的进入通知读取 @This：类照常加载、对象创建成功=true，通知执行 0 次；转换错误只交给监听器：[Order：IllegalStateException：Cannot map this reference for static method or constructor start: public lab.biz.Biz$Order()]
ctor.exit	改为出口通知读取 @This：[constructed Order2]
retransform	类已加载后安装：同一个对象的下一次调用就计数（累计 1）；removeTransformer 返回 true，之后调用仍计数（累计 2）；再对这个类调用 retransformClasses 才恢复原样（累计 2）
retransform.reset	ByteBuddy 的 reset 把移除与重新转换合在一起：reset 返回 true，之后不再计数（累计 1 → 1）；对已经 removeTransformer 过的转换器再 reset 返回 false（什么也不做）
retransform.schema	对已加载的类 retransform 时加一个字段：IllegalStateException：Could not transform any of {[class lab.biz.Biz$Schema]=java.lang.UnsupportedOperationException: class redefinition failed: attempted to change the schema (add/remove fields)}
twice	两个转换器都给 run 装了计数通知（例如两个 agent 都插桩同一个框架）：调用 1 次，计数 2
capture	截断到 16 个字符并遮掉数字：卡号参数采到 ****************；密码参数采到 example_password…（共 28 字符）
queue.blocking	上报队列容量 5、没有消费者，上报用 put：业务调用完成 5 次后卡住=true
queue.drop	改用 offer、满了丢弃并计数：20 次业务调用全部完成（小于 500ms），队列里 5 条，丢弃 15 条
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
