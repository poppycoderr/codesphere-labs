#!/usr/bin/env bash
# 失败路径上的写法：-O 下的 assert、finally 里的 return、裸 except、原地写与原子替换、子进程返回码、线程池里的异常
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14.8 容器，只用标准库；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w" -w /w "$PY" sh -c 'python src/failure_paths.py; python -O src/failure_paths.py' >"$f" 2>"$OUT/stderr.txt"
[ -s "$OUT/stderr.txt" ] || rm -f "$OUT/stderr.txt"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
# 代码是确定的单线程程序：输出与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env.普通	python=3.14.8，sys.flags.optimize=0，__debug__=True
assert.普通	withdraw(100, -50)，校验写成 assert：抛出 AssertionError
raise.普通	校验写成 if … raise ValueError：抛出 ValueError
assert.tuple	assert (1 > 2, 'msg') 执行通过；编译时的警告：SyntaxWarning
finally.return	try 里抛异常、finally 里 return 'ok'：调用 返回 'ok'；编译时的警告：SyntaxWarning
except.bare	try 里 sys.exit(3)，用裸 except：返回 '被裸 except 拦住，进程没有退出'；用 except Exception：抛出 SystemExit
write.in_place	原地重写配置文件、写到一半出错：抛出 RuntimeError；文件现在是 '{"version": 2, '
write.atomic	先写临时文件再 os.replace、写到一半出错：抛出 RuntimeError；文件现在是 '{"version": 1, "limit": 100}'，残留临时文件 False
write.atomic_ok	同一个函数正常写完：文件现在是 '{"version": 2, "limit": 200}'
subprocess.unchecked	subprocess.run(失败的命令)：没有异常，returncode=2
subprocess.checked	加上 check=True：抛出 CalledProcessError
pool.submit	submit 之后不调用 result()：离开 with 时没有异常；future.exception() 是 ValueError
pool.map	map 之后不遍历结果：没有异常；开始遍历时 抛出 ValueError
env.-O	python=3.14.8，sys.flags.optimize=1，__debug__=False
assert.-O	withdraw(100, -50)，校验写成 assert：返回 150
raise.-O	校验写成 if … raise ValueError：抛出 ValueError
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
