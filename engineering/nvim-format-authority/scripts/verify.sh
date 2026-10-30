#!/usr/bin/env bash
# 保存时格式化，谁说了算：两个格式化服务、按名字过滤、同步超时、异步结果与用户编辑、BufWritePre 里的异步格式化、已移除的旧接口（Neovim 0.12.6）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器；Neovim 0.12.6 官方 Linux 包按 SHA-256 校验后解压到 $LABS_CACHE；约 20 秒（首次下载约 15 MB）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
require curl
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
V=0.12.6
case "$(docker run --rm --entrypoint uname "$PY" -m)" in
  aarch64) A=arm64; SUM=8f1f64a0bdb97247034038c3823c6cbad5bdf9ecd5751b85494b71c3ee04c815 ;;
  x86_64) A=x86_64; SUM=474430d53e6264f6d6dd18db42d6dc9df3a1b56ca9e88a325bbf860e1a811d87 ;;
  *) fail "不支持的架构" ;;
esac
N="$LABS_CACHE/nvim-$V-$A"
if [ ! -x "$N/bin/nvim" ]; then
  mkdir -p "$N"
  curl -fsSL -o "$N.tar.gz" "https://github.com/neovim/neovim/releases/download/v$V/nvim-linux-$A.tar.gz" || fail "下载 Neovim 失败"
  echo "$SUM  $N.tar.gz" | shasum -a 256 -c - >/dev/null || fail "Neovim 包校验失败"
  tar -xzf "$N.tar.gz" -C "$N" --strip-components=1
fi
f="$OUT/output.tsv"
docker run --rm -v "$PWD/src:/src:ro" -v "$N:/nvim:ro" "$PY" /nvim/bin/nvim --clean --headless -l /src/lab.lua >"$f" 2>"$OUT/stderr.txt"
write_environment "$OUT/environment.txt" "python_image: $PY" "neovim: ${V}（nvim-linux-${A}.tar.gz，sha256 ${SUM:0:16}…）"
cat "$f" >&2
# 延迟与等待都留了余量，输出是确定的；Neovim 的提示信息（如 timeout）记在 stderr.txt
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
clients	同一个缓冲区挂了 2 个服务，都声明能格式化：fmt2=true、fmt4=true
both	vim.lsp.buf.format() 不加过滤：缩进 4/8（两个服务依次格式化，后一个的结果覆盖前一个）
filter.name	按名字只用 fmt2：缩进 2/4
filter.fn	用 filter 只用 fmt4：缩进 4/8
sync.timeout	服务要 1.5 秒、同步格式化 timeout_ms=300：调用返回（无错误），缓冲区 缩进 1/2
sync.late	超时之后结果再回来：缓冲区 缩进 1/2（迟到的结果被丢弃）
async.changed	async=true 发出请求后立即把第 1 行改成「def g():」：0.5 秒后结果返回，缓冲区第 1 行「def f():」，缩进 4/8；用户的修改被格式化结果覆盖=true
async.ok	同样的异步格式化、期间不改内容：缩进 4/8
save.sync	BufWritePre 里同步格式化后 :write：磁盘上第 2 行缩进 4，缓冲区 缩进 4/8，未保存的修改=false
save.async	BufWritePre 里 async=true：磁盘上第 2 行缩进 1（写的是格式化之前的内容），缓冲区 缩进 4/8，未保存的修改=true
old.api	client.resolved_capabilities = nil；vim.lsp.buf.formatting = nil；server_capabilities.documentFormattingProvider = true
env	nvim 0.12.6+v0.12.6
EXPECTED
log "全部通过，输出在 ${OUT}"
