#!/usr/bin/env bash
# 文件删除与打开句柄：删除、清空、改名一个仍被进程打开的文件之后，文件系统已用空间与目录下文件占用各是多少
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python 3.14 容器，挂一个 200 MB 的 tmpfs（占用内存，容器退出即释放）；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm --tmpfs /data:size=200m -v "$PWD/src:/src:ro" "$PY" python /src/deleted.py >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" "container_kernel: $(docker run --rm "$PY" uname -r)"
cat "$f" >&2
# 每一步都是同步的写入与系统调用，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
delete.before	进程写了 100 MB 日志	文件系统已用 100 MB	目录下文件合计 100 MB	目录内容 ['app.log']
delete.after_rm	rm app.log	文件系统已用 100 MB	目录下文件合计 0 MB	目录内容 []	写入进程的 fd 指向 /data/app.log (deleted)
delete.keep_writing	进程继续写 50 MB	文件系统已用 150 MB	目录下文件合计 0 MB	目录内容 []
delete.readable	通过 /proc/<pid>/fd 仍可读到内容	文件系统已用 150 MB	目录下文件合计 0 MB	目录内容 []	前 4 个字节 b'xxxx'
delete.truncate_via_proc	对 /proc/<pid>/fd/<n> 做截断	文件系统已用 0 MB	目录下文件合计 0 MB	目录内容 []
delete.closed	进程退出	文件系统已用 0 MB	目录下文件合计 0 MB	目录内容 []
truncate.append	写 100 MB、清空、再写 1 MB（O_APPEND）	文件系统已用 1 MB	目录下文件合计 1 MB	目录内容 ['append.log']	文件长度 1 MB，实际占用 1 MB
truncate.plain	写 100 MB、清空、再写 1 MB（未加 O_APPEND）	文件系统已用 1 MB	目录下文件合计 1 MB	目录内容 ['plain.log']	文件长度 101 MB，实际占用 1 MB
rename	写 10 MB、mv app.log app.log.1、再写 10 MB	文件系统已用 20 MB	目录下文件合计 20 MB	目录内容 ['app.log.1']	app.log.1 长度 20 MB，app.log 存在 = False
hardlink	20 MB 的文件有两个名字，删掉其中一个	文件系统已用 20 MB	目录下文件合计 20 MB	目录内容 ['b.bin']	剩余的名字的链接数 1
clean	全部删除并关闭之后	文件系统已用 0 MB	目录下文件合计 0 MB	目录内容 []
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
