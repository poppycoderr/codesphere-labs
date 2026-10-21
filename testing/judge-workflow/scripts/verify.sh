#!/usr/bin/env bash
# 判题结果的绑定与有界执行：8 个可信夹具在资源限制下的结果、输出比较、候选与判据摘要的绑定（Python 3.14 标准库）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，断网、只读根文件系统、非 root、去掉全部 capabilities、1 CPU、256 MB、64 个进程；约 15 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm --network=none --cpus=1 --memory=256m --pids-limit=64 --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  --user=65534:65534 --tmpfs=/tmp:rw,nosuid,nodev,size=64m -v "$PWD/src:/src:ro" "$PY" python -B /src/lab.py >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
# 夹具与限制固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	python=3.14.8
exec.正确求和	正确求和：正常退出，退出码 0，标准输出 2 字节
exec.写成减法	写成减法：正常退出，退出码 0，标准输出 3 字节
exec.语法错误	语法错误：编译检查拒绝
exec.运行时异常	运行时异常：非零退出，退出码 1，标准输出 0 字节
exec.长时间休眠	长时间休眠：墙钟超时，退出码 -9，标准输出 0 字节
exec.忙循环	忙循环：被信号 SIGXCPU 终止，退出码 -24，标准输出 0 字节
exec.申请 256 MiB	申请 256 MiB：内存申请失败，退出码 1，标准输出 0 字节
exec.大量输出	大量输出：输出超限，退出码 120，标准输出 8193 字节
exec.cpu_equal	忙循环、CPU 软硬限制都设为 1 秒：被信号 SIGKILL 终止，退出码 -9（与墙钟超时被杀的退出码相同）
judge.correct	正确求和：3 / 3 组通过
judge.wrong	写成减法：1 / 3 组通过（每次都正常退出，错在输出）
bind.accept	按确认时的候选与判据提交结果 res1：已接受
bind.duplicate	res1 原样重发：重复提交，保留原结果；换成 fail 重发：拒绝：同一结果 ID 换了内容
bind.candidate_changed	候选代码改过之后提交 res2：拒绝：候选代码与确认时不同，确认已失效
bind.oracle_changed	判据改过之后提交 res3：拒绝：判据与确认时不同，确认已失效
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
