#!/bin/sh
# 硬换行与软折行：gq 对中英文的断行、J 合并中文行是否插空格、wrap 不改文件、gq 破坏代码块、modeline 的默认值与限制（Vim 9.1）
set -eu
export HOME=$(mktemp -d); cd "$HOME"
out() { printf '%s\t%s\n' "$1" "$2"; }
vimx() { vim -u NONE -i NONE -n -es -N "$@" -c 'qa!' </dev/null >/dev/null 2>&1 || true; }
lines() { awk '{ printf "%s%s", sep, $0; sep="⏎" }' "$1"; }

printf '缓存失效之后所有请求都打到数据库上，命中率几乎掉到零，扩容的那一刻最明显\n' > zh.txt
cp zh.txt z1; vimx z1 -c 'set textwidth=20' -c 'normal gqq' -c 'w'
out gq.default "textwidth=20，默认 formatoptions，gqq 一行纯中文：$(wc -l < z1) 行，$(lines z1)"
cp zh.txt z2; vimx z2 -c 'set textwidth=20 formatoptions+=mM' -c 'normal gqq' -c 'w'
out gq.mM "formatoptions+=mM：$(wc -l < z2) 行，$(lines z2)"

printf '第一行的结尾\n第二行的开头\nend of line one\nstart of line two\n' > j.txt
cp j.txt j1; vimx j1 -c '1join' -c '2join' -c 'w'
out join.default "默认 J 合并两行中文与两行英文：$(lines j1)"
cp j.txt j2; vimx j2 -c 'set formatoptions+=B' -c '1join' -c '2join' -c 'w'
out join.B "formatoptions+=B：$(lines j2)"

cp zh.txt w; before=$(md5sum < w); vimx w -c 'set wrap linebreak columns=20' -c 'w'
out wrap "set wrap linebreak 后保存：文件$( [ "$before" = "$(md5sum < w)" ] && echo 内容不变 || echo 被改变)，仍是 $(wc -l < w) 行"

cat > md.txt <<'M'
一段说明文字，
后面跟着代码：

```sh
make build
make test
```
M
vimx md.txt -c 'set textwidth=60' -c 'normal gggqG' -c 'w'
out gq.codeblock "对一段说明加代码块执行 gggqG：$(lines md.txt)"

printf 'text\n# vim: set textwidth=33 :\n' > ml.txt
vimx ml.txt -c "call writefile([string(&modeline) . ' ' . &textwidth], 'v')"
out modeline.root "以 root 运行、-N：modeline=$(cut -d' ' -f1 v)，textwidth=$(cut -d' ' -f2 v)"
vimx --cmd 'set modeline' ml.txt -c "call writefile([string(&textwidth)], 'v')"
out modeline.on "set modeline 之后打开同一个文件：textwidth=$(cat v)"
printf 'text\n# vim: set foldmethod=expr foldexpr=Danger() :\n' > ml2.txt
vimx --cmd 'set modeline' ml2.txt -c "call writefile([execute('messages')->split('\n')->filter('v:val =~ \"E992\"')->get(0, 'none')->trim() . '；foldexpr 仍是默认值 ' . &foldexpr], 'v')"
out modeline.expr "modeline 里设置 foldexpr：$(cat v)"
