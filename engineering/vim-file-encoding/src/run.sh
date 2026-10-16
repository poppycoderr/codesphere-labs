#!/bin/sh
# 用 iconv 生成 GBK 与 UTF-8 文件，在空配置的 Vim 里用不同的 fileencodings 打开，输出探测结果、读到的第一行与保存后的字节
set -eu
W=$(mktemp -d); cd "$W"
V="vim -Nu NONE -i NONE -n -es"
out() { printf '%s\t%s\n' "$1" "$2"; }
hex() { od -An -tx1 "$1" | tr -s ' \n' ' ' | sed 's/^ //; s/ $//'; }
printf '中文测试\n' > utf8.txt
iconv -f UTF-8 -t GBK utf8.txt > gbk.txt
printf '\357\273\277中文\n' > bom.txt
printf '你好\n' > nihao.txt
out bytes "utf8.txt：$(hex utf8.txt)；gbk.txt：$(hex gbk.txt)"
probe() { # 名称 文件 额外命令
  $V -c "$3" -c "e $2" -c "call writefile([&encoding . ' / ' . &fileencodings . ' → fileencoding=' . &fileencoding . '，第一行：' . getline(1)], 'probe')" -c 'qa!'
  out "$1" "$(cat probe)"
}
probe default.gbk gbk.txt 'set nocp'
probe default.utf8 utf8.txt 'set nocp'
probe fencs_gbk.gbk gbk.txt 'set fencs=ucs-bom,utf-8,gbk,latin1'
probe fencs_gbk.utf8 utf8.txt 'set fencs=ucs-bom,utf-8,gbk,latin1'
probe fencs_gbk_first.utf8 utf8.txt 'set fencs=ucs-bom,gbk,utf-8,latin1'
probe fencs_gbk_first.nihao nihao.txt 'set fencs=ucs-bom,gbk,utf-8,latin1'
probe bom.default bom.txt 'set nocp'
$V -c 'e ++enc=gbk gbk.txt' -c "call writefile(['++enc=gbk 重新打开：fileencoding=' . &fileencoding . '，第一行：' . getline(1)], 'probe')" -c 'qa!'; out reopen.enc "$(cat probe)"
cp gbk.txt conv.txt
$V -c 'set fencs=ucs-bom,utf-8,gbk,latin1' -c 'e conv.txt' -c 'set fileencoding=utf-8' -c 'w' -c 'qa!'
out save.convert "以 gbk 打开后 set fileencoding=utf-8 再保存：$(hex conv.txt)"
cp bom.txt nobom.txt
$V -c 'e nobom.txt' -c 'set nobomb' -c 'w' -c 'qa!'
out save.nobomb "带 BOM 的文件 set nobomb 后保存：$(hex nobom.txt)"
cp gbk.txt latin.txt
$V -c 'e latin.txt' -c 'normal! A!' -c 'w' -c 'qa!'
out save.latin1_roundtrip "按 latin1 打开 GBK 文件、加一个字符后保存：$(hex latin.txt)（原有字节未变）"
