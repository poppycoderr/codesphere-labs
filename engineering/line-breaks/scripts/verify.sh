#!/usr/bin/env bash
# 硬换行、软折行与渲染：Vim 的 gq、J、wrap、modeline 对中英文的处理，以及段落内换行在三种浏览器里显示成什么（Vim 9.1、Playwright 1.63.0）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：基于固定 digest 的 Debian trixie 构建一个装有 vim-nox 的镜像；固定 digest 的 Playwright 镜像（自带三种浏览器，约 2.4 GB）；约 1 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG=csl-vim:9.1.1230
PW="mcr.microsoft.com/playwright/python@sha256:72bd171a9ffc2b4b59532aaa6210e21014d07093120dc25528870c0b840da1f0"
docker build -q -t "$IMG" . >/dev/null || fail "镜像构建失败"
f="$OUT/output.tsv"
docker run --rm -v "$PWD/src:/src:ro" "$IMG" sh /src/vim.sh >"$f" 2>/dev/null
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PW" sh -c '
  pip install -q --root-user-action=ignore --disable-pip-version-check --break-system-packages -r requirements.txt >/dev/null 2>&1
  python3 src/render.py' 2>/dev/null >>"$f"
{ echo "base_image: $(sed -n 's/^FROM //p' Dockerfile)"; echo "vim_package: vim-nox=2:9.1.1230-2"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "vim: 9.1（Debian trixie vim-nox 2:9.1.1230-2）" "playwright_image: ${PW}（v1.63.0-noble）" "markdown-it-py: 4.2.0"
cat "$f" >&2
# 输入固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
gq.default	textwidth=20，默认 formatoptions，gqq 一行纯中文：1 行，缓存失效之后所有请求都打到数据库上，命中率几乎掉到零，扩容的那一刻最明显
gq.mM	formatoptions+=mM：4 行，缓存失效之后所有请求⏎都打到数据库上，命中⏎率几乎掉到零，扩容的⏎那一刻最明显
join.default	默认 J 合并两行中文与两行英文：第一行的结尾 第二行的开头⏎end of line one start of line two
join.B	formatoptions+=B：第一行的结尾第二行的开头⏎end of line one start of line two
wrap	set wrap linebreak 后保存：文件内容不变，仍是 1 行
gq.codeblock	对一段说明加代码块执行 gggqG：一段说明文字， 后面跟着代码：⏎⏎```sh make build make test ```
modeline.root	以 root 运行、-N：modeline=0，textwidth=0
modeline.on	set modeline 之后打开同一个文件：textwidth=33
modeline.expr	modeline 里设置 foldexpr：E992: Not allowed in a modeline when 'modelineexpr' is off: foldexpr=Danger()；foldexpr 仍是默认值 0
markdown	markdown-it-py 4.2.0（CommonMark）渲染两段软换行的结果：<p>中文段落的第一行⏎接着写第二行</p>⏎<p>first line of text⏎second line</p>⏎
browser.chromium	chromium 153.0.8010.12：两个汉字之间的换行显示成空格，两个英文单词之间的换行显示成空格
browser.firefox	firefox 155.0：两个汉字之间的换行不显示，两个英文单词之间的换行显示成空格
browser.webkit	webkit 26.6：两个汉字之间的换行显示成空格，两个英文单词之间的换行显示成空格
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
