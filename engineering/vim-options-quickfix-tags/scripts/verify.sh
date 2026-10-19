#!/usr/bin/env bash
# Vim 的局部选项、Tab 相关选项、quickfix 与 errorformat、外部工具读磁盘、tags 的搜索模式与陈旧索引（Vim 9.1、Universal Ctags 5.9）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：基于固定 digest 的 Debian trixie 构建一个装有 vim-nox 与 universal-ctags 的镜像；约 1 分钟（首次构建需下载软件包）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG=csl-vim-ctags:9.1.1230
docker build -q -t "$IMG" . >/dev/null || fail "镜像构建失败"
f="$OUT/output.tsv"
docker run --rm -v "$PWD/src:/src:ro" "$IMG" sh /src/run.sh >"$f" 2>/dev/null
{ echo "base_image: $(sed -n 's/^FROM //p' Dockerfile)"; echo "packages: vim-nox=2:9.1.1230-2 universal-ctags=5.9.20210829.0-2"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "vim: 9.1（Debian trixie vim-nox 2:9.1.1230-2）" "ctags: Universal Ctags 5.9（Debian 5.9.20210829.0-2）"
cat "$f" >&2
# 输入与配置固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
option.setlocal	a.txt 里 setlocal sw=2 后打开 b.txt：b.txt 的 shiftwidth=8
option.set	a.txt 里 set sw=2 后打开 b.txt：b.txt 的 shiftwidth=2（set 同时改了全局值）
option.ftplugin	ftplugin/yaml.vim 里写 set sw=2：c.yaml 的 shiftwidth=2，之后打开的 b.txt 也是 2
tab.expandtab.1	set expandtab shiftwidth=4 softtabstop=4，按 1 次 Tab 再输入 x：文件内容 ····x（· 为空格，→ 为制表符）
tab.expandtab.2	set expandtab shiftwidth=4 softtabstop=4，按 2 次 Tab 再输入 x：文件内容 ········x（· 为空格，→ 为制表符）
tab.noexpandtab.1	set noexpandtab tabstop=8 softtabstop=4 shiftwidth=4，按 1 次 Tab 再输入 x：文件内容 ····x（· 为空格，→ 为制表符）
tab.noexpandtab.2	set noexpandtab tabstop=8 softtabstop=4 shiftwidth=4，按 2 次 Tab 再输入 x：文件内容 →x（· 为空格，→ 为制表符）
qf.default	默认 errorformat 解析三行日志：有效 src/order.c:12 error: 'total' undeclared|有效 my dir/pay.c:3 warning: unused variable 'x'|无效 :0 ERROR [src/report.py line 7] name 'rows' is not defined
qf.custom	加上 errorformat 'ERROR [%f line %l] %m'：3 条有效；第三条：src/report.py:7 name 'rows' is not defined
grep.disk	删掉第 2 行但不保存：:grep（外部 grep 读磁盘）找到 1 处，:vimgrep（读缓冲区）找到 0 处
tags.entry	ctags 为 total 生成的条目：/^int total(int n) { return price(n) + 5; }$/
tags.moved	在文件开头插入 3 行、不重建 tags：:tag total 跳到第 5 行（按搜索模式找，行号变化不影响）
tags.renamed	把 total 改名为 grand_total、不重建 tags：:tag total 报 Vim(tag):E434: Can't find tag pattern
tags.append	ctags -a 追加后：tags 里仍有 total 的条目 1 条，grand_total 1 条
tags.rebuild	重新生成 tags：total 0 条，grand_total 1 条
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
