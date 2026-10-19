#!/usr/bin/env bash
# Vim 脚本：重复 source、augroup、函数重定义、变量作用域、映射再解释、execute 与 fnameescape、job 的退出状态（Vim 9.1）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：基于固定 digest 的 Debian trixie 构建一个装有 vim-nox 2:9.1.1230-2 的镜像；约 1 分钟（首次构建需下载软件包）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG=csl-vim:9.1.1230
docker build -q -t "$IMG" . >/dev/null || fail "镜像构建失败"
f="$OUT/output.tsv"
docker run --rm -v "$PWD/src:/src:ro" "$IMG" sh /src/run.sh >"$f" 2>/dev/null
{ echo "base_image: $(sed -n 's/^FROM //p' Dockerfile)"; echo "vim_package: vim-nox=2:9.1.1230-2"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "vim: 9.1（Debian trixie vim-nox 2:9.1.1230-2）"
cat "$f" >&2
# 输入与配置固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
autocmd.bare	bare.vim source 3 次后保存一次：自动命令执行了 3 次
autocmd.grouped	grouped.vim source 3 次后保存一次：自动命令执行了 1 次
func.resource	同一个脚本里没有 ! 的 function，第二次 source：没有报错，Hello() = 1
func.other	另一个脚本定义同名函数：Vim(function):E122: Function Hello already exists, add ! to replace it
scope.script	lib.vim 定义 s:secret 与 s:Get()：另一个脚本里 exists("s:secret")=0，exists("g:shared")=1，exists("*s:Get")=0
scope.buffer	a.txt 设 b:mode 后切到 b.txt：exists(b:mode)=0；切回 a.txt：b:mode=a
map.recursive	先 nnoremap d x，再 nmap D d$，在行首按 D：ello world（d 被再解释成 x，$ 只是移动）
map.noremap	改成 nnoremap D d$：行内容「」（按内置的 d 删除到行尾）
exec.raw	在 a.txt 里 execute 'write ' . f，f = 'report#1.txt'：写出的文件是 reporta.txt.txt（#1 被展开成 1 号缓冲区的文件名）
exec.escaped	改用 fnameescape(f)：写出的文件是 report#1.txt
job.fixed	启动一个 0.5 秒后以 3 退出的作业，固定等 100 毫秒：status=run，exitval=0（作业还没结束，这个 0 不是退出码）
job.wait	等到 job_status 不再是 run（最多 5 秒）：status=dead，exitval=3
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
