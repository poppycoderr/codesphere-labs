#!/usr/bin/env bash
# Vim 打开中文文件：encoding、fileencodings 的探测顺序、fileencoding 与保存时的转换、BOM（Vim 9.1，空配置）
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
bytes	utf8.txt：e4 b8 ad e6 96 87 e6 b5 8b e8 af 95 0a；gbk.txt：d6 d0 ce c4 b2 e2 ca d4 0a
default.gbk	utf-8 / ucs-bom,utf-8,default,latin1 → fileencoding=latin1，第一行：ÖÐÎÄ²âÊÔ
default.utf8	utf-8 / ucs-bom,utf-8,default,latin1 → fileencoding=utf-8，第一行：中文测试
fencs_gbk.gbk	utf-8 / ucs-bom,utf-8,gbk,latin1 → fileencoding=cp936，第一行：中文测试
fencs_gbk.utf8	utf-8 / ucs-bom,utf-8,gbk,latin1 → fileencoding=utf-8，第一行：中文测试
fencs_gbk_first.utf8	utf-8 / ucs-bom,gbk,utf-8,latin1 → fileencoding=utf-8，第一行：中文测试
fencs_gbk_first.nihao	utf-8 / ucs-bom,gbk,utf-8,latin1 → fileencoding=cp936，第一行：浣犲ソ
bom.default	utf-8 / ucs-bom,utf-8,default,latin1 → fileencoding=utf-8，第一行：中文
reopen.enc	++enc=gbk 重新打开：fileencoding=cp936，第一行：中文测试
save.convert	以 gbk 打开后 set fileencoding=utf-8 再保存：e4 b8 ad e6 96 87 e6 b5 8b e8 af 95 0a
save.nobomb	带 BOM 的文件 set nobomb 后保存：e4 b8 ad e6 96 87 0a
save.latin1_roundtrip	按 latin1 打开 GBK 文件、加一个字符后保存：d6 d0 ce c4 b2 e2 ca d4 21 0a（原有字节未变）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
