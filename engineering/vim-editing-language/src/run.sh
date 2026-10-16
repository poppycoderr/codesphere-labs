#!/bin/sh
# 在空配置（-Nu NONE）的 Vim 里执行一组编辑命令，输出命令执行后的文本
set -eu
W=$(mktemp -d); cd "$W"
V="vim -Nu NONE -i NONE -n -es"
out() { printf '%s\t%s\n' "$1" "$2"; }
show() { tr '\n' '|' < "$1" | sed 's/|$//'; }
out version "$(vim --version | head -1 | sed 's/ (.*//')"

printf 'the quick brown fox\n' > t
for cmd in dw diw daw; do
  cp t t2; $V -c 'call cursor(1, 7)' -c "normal! $cmd" -c 'wq' t2; out "object.$cmd" "光标在 quick 的 i 上，$cmd → $(show t2)"
done
printf 'f(a, g(b, c), d)\n' > p
cp p p2; $V -c 'call cursor(1, 8)' -c 'normal! ci(X' -c 'wq' p2; out "object.ci_paren" "光标在 b 上，ci( 改成 X → $(show p2)"
cp p p2; $V -c 'call cursor(1, 8)' -c 'normal! c2i(X' -c 'wq' p2; out "object.c2i_paren" "同一位置，c2i( 改成 X → $(show p2)"

printf 'x = 1;\ny = 2;\nz = 3;\n' > r
$V -c 'call cursor(1, 1)' -c 'normal! A // checked' -c 'normal! j.' -c 'normal! j.' -c 'wq' r; out "repeat.dot" "第一行 A // checked，然后 j. 两次 → $(show r)"

printf 'keep\ntrash\nend\n' > g
$V -c 'call cursor(1, 1)' -c 'normal! yy' -c 'normal! jdd' -c 'normal! p' -c 'normal! "0p' -c 'wq' g
out "register.unnamed_vs_0" "第 1 行 yy，第 2 行 dd，然后 p 再 \"0p → $(show g)"

printf 'a,1\nb,2\nc,3\n' > m
$V -c 'let @a = "0\"zdt,xA,\<C-r>z\<Esc>j"' -c 'normal! gg3@a' -c 'wq' m; out "macro.count" "宏「交换逗号两侧」执行 3@a → $(show m)"
printf 'a,1\nd\nc,3\n' > m2
$V -c 'let @a = "0\"zdt,xA,\<C-r>z\<Esc>j"' -c 'normal! gg3@a' -c 'wq' m2; out "macro.stops_on_error" "第 2 行没有逗号，3@a → $(show m2)"
printf 'a,1\nd\nc,3\n' > m3
$V -c 'let @a = "0\"zdt,xA,\<C-r>z\<Esc>"' -c 'g/,/normal! @a' -c 'wq' m3; out "macro.global" ":g/,/normal @a 只作用于有逗号的行 → $(show m3)"

printf 'TODO fix login\nok\nTODO add test\n' > todo
$V -c 'g/^TODO/normal! A (alice)' -c 'wq' todo; out "global.normal" ":g/^TODO/normal A (alice) → $(show todo)"

printf 'width = 10\nheight=20\n' > s
$V -c '%s/\v(\w+)\s*\=\s*(\w+)/\2 = \1/' -c 'wq' s; out "substitute.very_magic" ":%s/\\v(\\w+)\\s*\\=\\s*(\\w+)/\\2 = \\1/ → $(show s)"
printf 'price: 199\nprice: 5\n' > z
$V -c '%s/price: \zs\d\+/***/' -c 'wq' z; out "substitute.zs" ":%s/price: \\zs\\d\\+/***/ 只替换数字 → $(show z)"

printf 'foo one\n' > f1.txt; printf 'two foo\n' > f2.txt; printf 'foo\n' > skip.md
$V -c 'args *.txt' -c 'argdo %s/foo/bar/ge | update' -c 'qa' ; out "arglist.argdo" ":args *.txt | argdo %s/foo/bar/ge | update → f1.txt: $(show f1.txt)；f2.txt: $(show f2.txt)；skip.md: $(show skip.md)"
