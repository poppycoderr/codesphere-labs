#!/bin/sh
# Vim 脚本：重复 source 时的自动命令与函数、变量作用域、映射是否再解释、execute 与文件名转义、job 的退出状态与固定等待（Vim 9.1）
set -eu
export HOME=$(mktemp -d); cd "$HOME"
out() { printf '%s\t%s\n' "$1" "$2"; }
vimx() { vim -u NONE -i NONE -n -es -N "$@" -c 'qa!' </dev/null >/dev/null 2>&1 || true; }
v() { cat v; }
printf 'line\n' > a.txt

cat > bare.vim <<'V'
let g:n = 0
autocmd BufWritePre * let g:n += 1
V
cat > grouped.vim <<'V'
let g:n = 0
augroup my_count
  autocmd!
  autocmd BufWritePre * let g:n += 1
augroup END
V
for f in bare grouped; do
  vimx a.txt -c "source $f.vim" -c "source $f.vim" -c "source $f.vim" -c 'write' -c "call writefile([string(g:n)], 'v')"
  out "autocmd.$f" "$f.vim source 3 次后保存一次：自动命令执行了 $(v) 次"
done

printf 'function Hello()\n  return 1\nendfunction\n' > f1.vim; cp f1.vim f3.vim
vimx -c 'source f1.vim' -c 'source f1.vim' -c "call writefile(['同一个脚本里没有 ! 的 function，第二次 source：没有报错，Hello() = ' . Hello()], 'v')"
out func.resource "$(v)"
vimx -c 'source f1.vim' -c 'try | execute "source f3.vim" | catch | call writefile([v:exception], "v") | endtry'
out func.other "另一个脚本定义同名函数：$(v)"
printf 'let s:secret = 42\nlet g:shared = 1\nfunction s:Get()\n  return s:secret\nendfunction\n' > lib.vim
vimx -c 'source lib.vim' -c "call writefile(['另一个脚本里 exists(\"s:secret\")=' . exists('s:secret') . '，exists(\"g:shared\")=' . exists('g:shared') . '，exists(\"*s:Get\")=' . exists('*s:Get')], 'v')"
out scope.script "lib.vim 定义 s:secret 与 s:Get()：$(v)"
printf 'b\n' > b.txt
vimx a.txt -c 'let b:mode = "a"' -c 'edit b.txt' -c "let g:r = exists('b:mode')" -c 'edit a.txt' -c "call writefile(['a.txt 设 b:mode 后切到 b.txt：exists(b:mode)=' . g:r . '；切回 a.txt：b:mode=' . b:mode], 'v')"
out scope.buffer "$(v)"

printf 'hello world\n' > m.txt
vimx m.txt -c 'nnoremap d x' -c 'nmap D d$' -c 'normal D' -c 'write' 
out map.recursive "先 nnoremap d x，再 nmap D d\$，在行首按 D：$(cat m.txt)（d 被再解释成 x，\$ 只是移动）"
printf 'hello world\n' > m.txt
vimx m.txt -c 'nnoremap d x' -c 'nnoremap D d$' -c 'normal D' -c 'write'
out map.noremap "改成 nnoremap D d\$：行内容「$(cat m.txt)」（按内置的 d 删除到行尾）"

vimx a.txt -c "let f = 'report#1.txt'" -c 'execute "write " . f'
out exec.raw "在 a.txt 里 execute 'write ' . f，f = 'report#1.txt'：写出的文件是 $(ls report* | tr '\n' ' ' | sed 's/ $//')（#1 被展开成 1 号缓冲区的文件名）"
rm -f report*
vimx a.txt -c "let f = 'report#1.txt'" -c 'execute "write " . fnameescape(f)'
out exec.escaped "改用 fnameescape(f)：写出的文件是 $(ls report*)"

vimx -c "let j = job_start(['sh', '-c', 'sleep 0.5; exit 3'])" -c 'sleep 100m' -c "call writefile(['启动一个 0.5 秒后以 3 退出的作业，固定等 100 毫秒：status=' . job_status(j) . '，exitval=' . job_info(j).exitval . '（作业还没结束，这个 0 不是退出码）'], 'v')"
out job.fixed "$(v)"
vimx -c "let j = job_start(['sh', '-c', 'sleep 0.5; exit 3'])" -c "let t = 0 | while job_status(j) ==# 'run' && t < 50 | sleep 100m | let t += 1 | endwhile" -c "call writefile(['等到 job_status 不再是 run（最多 5 秒）：status=' . job_status(j) . '，exitval=' . job_info(j).exitval], 'v')"
out job.wait "$(v)"
