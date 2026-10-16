#!/usr/bin/env bash
# Vim 的编辑语言：操作符与动作、文本对象、点号重复、寄存器、宏与 :g、very magic 与 \zs、参数列表（Vim 9.1，空配置）
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
version	VIM - Vi IMproved 9.1
object.dw	光标在 quick 的 i 上，dw → the qubrown fox
object.diw	光标在 quick 的 i 上，diw → the  brown fox
object.daw	光标在 quick 的 i 上，daw → the brown fox
object.ci_paren	光标在 b 上，ci( 改成 X → f(a, g(X), d)
object.c2i_paren	同一位置，c2i( 改成 X → f(X)
repeat.dot	第一行 A // checked，然后 j. 两次 → x = 1; // checked|y = 2; // checked|z = 3; // checked
register.unnamed_vs_0	第 1 行 yy，第 2 行 dd，然后 p 再 "0p → keep|end|trash|keep
macro.count	宏「交换逗号两侧」执行 3@a → 1,a|2,b|3,c
macro.stops_on_error	第 2 行没有逗号，3@a → 1,a|d|c,3
macro.global	:g/,/normal @a 只作用于有逗号的行 → 1,a|d|3,c
global.normal	:g/^TODO/normal A (alice) → TODO fix login (alice)|ok|TODO add test (alice)
substitute.very_magic	:%s/\v(\w+)\s*\=\s*(\w+)/\2 = \1/ → 10 = width|20 = height
substitute.zs	:%s/price: \zs\d\+/***/ 只替换数字 → price: ***|price: ***
arglist.argdo	:args *.txt | argdo %s/foo/bar/ge | update → f1.txt: bar one；f2.txt: two bar；skip.md: foo
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
