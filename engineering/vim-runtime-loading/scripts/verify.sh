#!/usr/bin/env bash
# Vim 的配置与插件怎样加载：runtimepath、plugin 与 after/plugin 的顺序、pack 的 start 与 opt、autoload 的按需加载、ftplugin（Vim 9.1）
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
runtimepath	~/.vim ~/.vim/pack/my/start/foo /var/lib/vim/addons /etc/vim /usr/share/vim/vimfiles /usr/share/vim/vim91 /usr/share/vim/vim91/pack/dist/opt/netrw /usr/share/vim/vimfiles/after /etc/vim/after /var/lib/vim/addons/after ~/.vim/after
order.startup	vimrc plugin/a.vim pack/my/start/foo after/plugin/a.vim
override.after	g:who = after/plugin/a.vim（vimrc、plugin、after/plugin 依次赋值，最后的生效）
autoload.before	启动后 util#hello 是否已定义：undefined；autoload/util.vim 是否已加载：0
autoload.call	调用 util#hello() 返回「hello from autoload」，此时加载日志：vimrc plugin/a.vim pack/my/start/foo after/plugin/a.vim autoload/util.vim
pack.opt	执行 :packadd bar 之后的加载日志：vimrc plugin/a.vim pack/my/start/foo after/plugin/a.vim pack/my/opt/bar
ftplugin.user	~/.vim/ftplugin/python.vim 设 shiftwidth=3，打开 demo.py：python shiftwidth=4，最后设置它的脚本：/usr/share/vim/vim91/ftplugin/python.vim
ftplugin.after	再加 ~/.vim/after/ftplugin/python.vim 设 shiftwidth=2：python shiftwidth=2；日志：vimrc plugin/a.vim pack/my/start/foo after/plugin/a.vim ftplugin/python.vim after/ftplugin/python.vim
noplugin	--noplugin 启动：vimrc
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
