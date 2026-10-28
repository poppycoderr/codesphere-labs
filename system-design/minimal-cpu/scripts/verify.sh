#!/usr/bin/env bash
# 最小累加器 CPU：取指译码执行、同一时钟沿的状态更新、标志位契约、停机与挂起的写、非法操作码（Python 3.14.8）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$IMG" python -B src/lab.py 2>/dev/null >"$f"
docker run --rm -v "$PWD:/w" -w /w "$IMG" python -B -m unittest -v tests/test_cpu.py >"$OUT/unittest.txt" 2>&1 || { cat "$OUT/unittest.txt" >&2; fail "单元测试失败"; }
write_environment "$OUT/environment.txt" "image: $IMG"
cat "$f" >&2
# 输入固定、并发交错由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
mul	循环加法算 6 × 7：输出 [42]，61 步后停机
mul.zero	算 6 × 0：输出 [0]，5 步后停机
xch	ACC=5、M[0x80]=9 执行 XCH：逐句赋值得到 ACC=9、M=9；由旧状态算新状态得到 ACC=9、M=5
flags.lda	同一段乘法程序，约定改为 LDA 不更新 Z：输出 [0]，1029 步后停机（JZ 读到的是上一次 ADD 留下的 Z，结果累加到 256 回绕为 0 时才退出）
flags.fixed	在 JZ 前补一条 ADD 0（显式设置标志）：LDA 更新 Z 时输出 [42]（69 步），不更新时输出 [42]（69 步）
flags.carry	200 + 100：ACC=44，C=1，跳到了进位分支（8 位累加器只保留低 8 位）
halt	STA 之后紧跟 HLT，写回延后一拍：HLT 立即停机时 M[0x82]=0，先提交挂起的写再停机时 M[0x82]=42
illegal.00.fault	漏写 HLT，空白内存为 0，非法操作码按 fault 处理：3 步后停机，OUT 1 次
illegal.00.nop	漏写 HLT，空白内存为 0，非法操作码按 nop 处理：3 步后停机，OUT 1 次
illegal.ff.fault	漏写 HLT，空白内存为 0xFF，非法操作码按 fault 处理：2 步后非法操作码 0xFF，地址 0x04，OUT 1 次
illegal.ff.nop	漏写 HLT，空白内存为 0xFF，非法操作码按 nop 处理：1000 步后达到步数上限，OUT 8 次
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
