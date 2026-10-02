#!/usr/bin/env bash
# 同一份代码在 CPython 3.14.8 默认构建与 free-threaded 构建上：CPU 密集线程的并行度、阻塞等待、进程池、不加锁的计数、重新启用 GIL
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 uv 容器（限 4 个 CPU），两种构建由 uv 下载到 labs 缓存目录；首次约 1 分钟，之后约 20 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
UV="ghcr.io/astral-sh/uv:debian-slim@sha256:0d42a8146856d158fc883c0e9b92a026e1e2afb65772cd110b7fb3eb01a40e5e"
PYV=3.14.8
mkdir -p "$LABS_CACHE/uv-python"
f="$OUT/output.tsv"
docker run --rm --cpus 4 -v "$PWD:/w" -v "$LABS_CACHE/uv-python:/pythons" -e UV_PYTHON_INSTALL_DIR=/pythons -w /w "$UV" sh -c "
  set -e
  uv python install -q $PYV $PYV+freethreaded
  GIL=\$(uv python find $PYV); FT=\$(uv python find $PYV+freethreaded)
  { uv --version; \$GIL -VV; \$FT -VV; } >/w/$OUT/versions.txt
  \$GIL src/gil_bench.py gil
  \$FT src/gil_bench.py ft
  PYTHON_GIL=1 \$FT src/gil_bench.py ft_gil_on
" >"$f"
write_environment "$OUT/environment.txt" "uv_image: $UV" "python: $PYV 与 $PYV+freethreaded（uv 管理的 python-build-standalone 构建）" "container_cpus: 4"
cat "$f" >&2
expect_regex "$OUT/versions.txt" "^Python 3\.14\.8 free-threading build " "free-threaded 构建的 3.14.8"
expect_line "$f" "gil.build	Python 3.14.8，Py_GIL_DISABLED=0，运行时 GIL 启用=True" "默认构建"
expect_line "$f" "ft.build	Python 3.14.8，Py_GIL_DISABLED=1，运行时 GIL 启用=False" "free-threaded 构建，GIL 关闭"
expect_line "$f" "ft_gil_on.build	Python 3.14.8，Py_GIL_DISABLED=1，运行时 GIL 启用=True" "free-threaded 构建，PYTHON_GIL=1 重新启用 GIL"
expect_line "$f" "gil.counter.unsafe	4 个线程各做 20 万次 counter += 1（不加锁）：结果 正好 800000" "默认构建上这次没有丢失更新"
expect_line "$f" "ft.counter.unsafe	4 个线程各做 20 万次 counter += 1（不加锁）：结果 少于 800000" "free-threaded 构建上不加锁的计数丢失更新"
for l in gil ft ft_gil_on; do
  expect_line "$f" "$l.counter.locked	同样的计数加锁之后：结果 800000" "$l：加锁后计数正确"
  expect_line "$f" "$l.list.append	4 个线程各向同一个 list append 10 万次（不加锁）：长度 400000" "$l：list.append 不丢元素"
done
python3 - "$f" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
def cores(key):
    return float(re.search(rf"^{re.escape(key)}\t.*相当于 ([\d.]+) 个核", t, re.M)[1])
def ratio(key):
    return float(re.search(rf"^{re.escape(key)}\t.*的 ([\d.]+) 倍", t, re.M)[1])
single = {l: int(re.search(rf"^{l}\.cpu\.single_ms\t.*：(\d+)ms", t, re.M)[1]) for l in ("gil", "ft", "ft_gil_on")}
assert cores("gil.cpu.threads_4") < 1.3, cores("gil.cpu.threads_4")            # GIL：4 个线程只有 1 个核在算
assert cores("ft.cpu.threads_2") > 1.6 and cores("ft.cpu.threads_4") > 2.5, (cores("ft.cpu.threads_2"), cores("ft.cpu.threads_4"))
assert cores("ft_gil_on.cpu.threads_4") < 1.5, cores("ft_gil_on.cpu.threads_4")  # 同一个二进制，打开 GIL 后又回到 1 个核
assert cores("gil.cpu.processes_4") > 2.5, cores("gil.cpu.processes_4")
for l in ("gil", "ft", "ft_gil_on"):
    assert ratio(f"{l}.io.threads_4") < 1.3, (l, ratio(f"{l}.io.threads_4"))   # 阻塞等待在两种构建上都能重叠
    for n in (2, 4):
        assert cores(f"{l}.cpu.threads_{n}") <= n * 1.1, (l, n, cores(f"{l}.cpu.threads_{n}"))   # 并行度不应超过线程数：超过说明基准测得不准
slow = single["ft"] / single["gil"]
assert 0.8 < slow < 2.0, single
print(f"通过：默认构建 4 线程 {cores('gil.cpu.threads_4')} 核，free-threaded {cores('ft.cpu.threads_4')} 核，重新启用 GIL {cores('ft_gil_on.cpu.threads_4')} 核；单线程耗时比 {slow:.2f}")
PY
log "全部通过，输出在 $OUT"
