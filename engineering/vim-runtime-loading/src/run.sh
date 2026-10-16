#!/bin/sh
# 在一个临时 HOME 里搭出 ~/.vim 的目录结构，每个脚本被加载时往日志里写一行；启动 Vim 后输出加载顺序与几个变量
set -eu
export HOME=$(mktemp -d); cd "$HOME"
V=$HOME/.vim
mkdir -p $V/plugin $V/after/plugin $V/autoload $V/ftplugin $V/pack/my/start/foo/plugin $V/pack/my/opt/bar/plugin
log() { printf "call writefile(['%s'], \$HOME . '/load.log', 'a')\n" "$1"; }
{ echo 'set nocompatible'; log vimrc; echo 'filetype plugin on'; echo 'let g:who = "vimrc"'; } > $V/vimrc
{ log plugin/a.vim; echo 'let g:who = "plugin/a.vim"'; } > $V/plugin/a.vim
{ log after/plugin/a.vim; echo 'let g:who = "after/plugin/a.vim"'; } > $V/after/plugin/a.vim
log pack/my/start/foo > $V/pack/my/start/foo/plugin/foo.vim
log pack/my/opt/bar > $V/pack/my/opt/bar/plugin/bar.vim
{ log autoload/util.vim; echo 'function util#hello() abort'; echo '  return "hello from autoload"'; echo 'endfunction'; } > $V/autoload/util.vim
{ log ftplugin/python.vim; echo 'setlocal shiftwidth=3'; } > $V/ftplugin/python.vim
printf 'print(1)\n' > demo.py
out() { printf '%s\t%s\n' "$1" "$2"; }
run() { rm -f load.log; vim -i NONE -n --not-a-term -T dumb "$@" -c 'qa!' </dev/null >/dev/null 2>&1; }

run -c "call writefile([join(split(&runtimepath, ','), ' ')], 'rtp')"
out runtimepath "$(sed "s|$HOME|~|g" rtp)"
run
out order.startup "$(tr '\n' ' ' < load.log | sed 's/ $//')"
run -c "call writefile([g:who], 'v')"
out override.after "g:who = $(cat v)（vimrc、plugin、after/plugin 依次赋值，最后的生效）"
run -c "call writefile([exists('*util#hello') ? 'defined' : 'undefined'], 'v')" ; out autoload.before "启动后 util#hello 是否已定义：$(cat v)；autoload/util.vim 是否已加载：$(grep -c autoload load.log || true)"
run -c "call writefile([util#hello()], 'v')"; out autoload.call "调用 util#hello() 返回「$(cat v)」，此时加载日志：$(tr '\n' ' ' < load.log | sed 's/ $//')"
run -c 'packadd bar'; out pack.opt "执行 :packadd bar 之后的加载日志：$(tr '\n' ' ' < load.log | sed 's/ $//')"
run demo.py -c "call writefile([&filetype . ' shiftwidth=' . &shiftwidth . '，最后设置它的脚本：' . substitute(execute('verbose setlocal shiftwidth?'), '.*Last set from \\(\\S\\+\\).*', '\\1', '')], 'v')"; out ftplugin.user "~/.vim/ftplugin/python.vim 设 shiftwidth=3，打开 demo.py：$(sed "s|$HOME|~|g" v)"
mkdir -p $V/after/ftplugin; { log after/ftplugin/python.vim; echo 'setlocal shiftwidth=2'; } > $V/after/ftplugin/python.vim
run demo.py -c "call writefile([&filetype . ' shiftwidth=' . &shiftwidth], 'v')"; out ftplugin.after "再加 ~/.vim/after/ftplugin/python.vim 设 shiftwidth=2：$(cat v)；日志：$(tr '\n' ' ' < load.log | sed 's/ $//')"
run --noplugin; out noplugin "--noplugin 启动：$(tr '\n' ' ' < load.log | sed 's/ $//')"
