#!/bin/sh
# 选项作用域、Tab 相关的四个选项、quickfix 的 errorformat、工具读磁盘与 vimgrep 读缓冲区、tags 的搜索模式与陈旧索引（Vim 9.1）
set -eu
export HOME=$(mktemp -d); cd "$HOME"
out() { printf '%s\t%s\n' "$1" "$2"; }
vimx() { vim -u NONE -i NONE -n -es -N "$@" -c 'qa!' </dev/null >/dev/null 2>&1 || true; }
v() { cat v; }

printf 'a\n' > a.txt; printf 'b\n' > b.txt
vimx a.txt -c 'setlocal shiftwidth=2' -c 'edit b.txt' -c "call writefile(['a.txt 里 setlocal sw=2 后打开 b.txt：b.txt 的 shiftwidth=' . &sw], 'v')"
out option.setlocal "$(v)"
vimx a.txt -c 'set shiftwidth=2' -c 'edit b.txt' -c "call writefile(['a.txt 里 set sw=2 后打开 b.txt：b.txt 的 shiftwidth=' . &sw . '（set 同时改了全局值）'], 'v')"
out option.set "$(v)"

mkdir -p .vim/ftplugin; echo 'set shiftwidth=2' > .vim/ftplugin/yaml.vim; printf 'k: v\n' > c.yaml
vimx --cmd 'set rtp^=~/.vim' --cmd 'filetype plugin on' c.yaml -c "let g:y = &sw" -c 'edit b.txt' -c "call writefile(['ftplugin/yaml.vim 里写 set sw=2：c.yaml 的 shiftwidth=' . g:y . '，之后打开的 b.txt 也是 ' . &sw], 'v')"
out option.ftplugin "$(v)"

show() { sed -e 's/\t/→/g' -e 's/ /·/g' "$1"; }
for cfg in 'expandtab shiftwidth=4 softtabstop=4' 'noexpandtab tabstop=8 softtabstop=4 shiftwidth=4'; do
  for n in 1 2; do
    keys=$(printf 'i%*s' 0 ''; i=0; while [ $i -lt $n ]; do printf '\t'; i=$((i+1)); done; printf 'x')
    : > t.txt
    vimx t.txt -c "set $cfg" -c "call feedkeys(\"$keys\\<Esc>\", 'xt')" -c 'w'
    out "tab.$(echo $cfg | cut -d' ' -f1).$n" "set $cfg，按 $n 次 Tab 再输入 x：文件内容 $(show t.txt)（· 为空格，→ 为制表符）"
  done
done

cat > build.log <<'L'
src/order.c:12:5: error: 'total' undeclared
my dir/pay.c:3:1: warning: unused variable 'x'
ERROR [src/report.py line 7] name 'rows' is not defined
L
vimx -c 'cfile build.log' -c "call writefile(map(getqflist(), {_, e -> (e.valid ? '有效' : '无效') . ' ' . bufname(e.bufnr) . ':' . e.lnum . ' ' . trim(e.text)}), 'v')"
out qf.default "默认 errorformat 解析三行日志：$(paste -sd'|' v)"
vimx -c 'set errorformat^=ERROR\ [%f\ line\ %l]\ %m' -c 'cfile build.log' -c "call writefile([len(filter(getqflist(), 'v:val.valid')) . ' 条有效；第三条：' . bufname(getqflist()[2].bufnr) . ':' . getqflist()[2].lnum . ' ' . getqflist()[2].text], 'v')"
out qf.custom "加上 errorformat 'ERROR [%f line %l] %m'：$(v)"

printf 'ok\nBUG here\nok\n' > code.txt
vimx code.txt -c '2delete' -c 'set grepprg=grep\ -n\ $*\ /dev/null' -c 'silent grep BUG code.txt' -c "let g:a = len(getqflist())" -c 'try | vimgrep /BUG/ % | catch | endtry' -c "call writefile(['删掉第 2 行但不保存：:grep（外部 grep 读磁盘）找到 ' . g:a . ' 处，:vimgrep（读缓冲区）找到 ' . len(getqflist()) . ' 处'], 'v')"
out grep.disk "$(v)"

cat > shop.c <<'C'
int price(int n) { return n * 10; }
int total(int n) { return price(n) + 5; }
C
ctags -f tags shop.c
out tags.entry "ctags 为 total 生成的条目：$(grep '^total' tags | cut -f3 | sed 's/;"$//')"
printf '/* header */\n/* header */\n/* header */\n' | cat - shop.c > s && mv s shop.c
vimx -c 'tag total' -c "call writefile(['在文件开头插入 3 行、不重建 tags：:tag total 跳到第 ' . line('.') . ' 行（按搜索模式找，行号变化不影响）'], 'v')"
out tags.moved "$(v)"
sed -i 's/int total(int n)/int grand_total(int n)/' shop.c
vimx -c 'try | tag total | catch | call writefile([v:exception], "v") | endtry'
out tags.renamed "把 total 改名为 grand_total、不重建 tags：:tag total 报 $(v)"
ctags -a -f tags shop.c
out tags.append "ctags -a 追加后：tags 里仍有 total 的条目 $(grep -c '^total	' tags) 条，grand_total $(grep -c '^grand_total	' tags) 条"
ctags -f tags shop.c
out tags.rebuild "重新生成 tags：total $(grep -c '^total	' tags) 条，grand_total $(grep -c '^grand_total	' tags) 条"
