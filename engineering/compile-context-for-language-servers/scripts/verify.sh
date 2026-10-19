#!/usr/bin/env bash
# 语言服务器看到的编译上下文：没有编译数据库、CMake 生成的数据库、构建改了宏而数据库没更新（clangd 19、CMake 3.31）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：基于固定 digest 的 Debian trixie 构建一个装有 clangd、clang 与 CMake 的镜像；约 2 分钟（首次构建需下载软件包）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
IMG=csl-clangd:19.1.7
docker build -q -t "$IMG" . >/dev/null || fail "镜像构建失败"
f="$OUT/output.tsv"
docker run --rm -v "$PWD/src:/src:ro" "$IMG" sh /src/run.sh >"$f" 2>/dev/null
{ echo "base_image: $(sed -n 's/^FROM //p' Dockerfile)"; echo "packages: clangd=1:19.0-63 clang=1:19.0-63 cmake=3.31.6-2 make=4.4.1-2"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "clangd: 19.1.7（Debian trixie 1:19.0-63）" "cmake: 3.31.6"
cat "$f" >&2
# 输入与配置固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	Debian clangd version 19.1.7；cmake version 3.31.6
none	没有 compile_commands.json：2 个错误：Line 2: 'config.h' file not found|Line 8: use of undeclared identifier 'MAX_ITEMS'
cdb.command	CMake 生成的编译命令：/usr/bin/clang -Iinclude -o CMakeFiles/shop.dir/src/main.c.o -c src/main.c
cdb	用 build/ 的编译数据库：0 个错误
build.v2	构建改成 -DUSE_V2=ON：cmake --build 失败：error: call to undeclared function 'v2_total'; ISO C99 and later do not support implicit function declarations [-Wimplicit-function-declaration]
stale	编辑器仍指向构建前生成、不含 USE_V2 的数据库：0 个错误
fresh.command	构建后的编译命令：/usr/bin/clang -DUSE_V2 -Iinclude -o CMakeFiles/shop.dir/src/main.c.o -c src/main.c
fresh	换成与构建一致的数据库：1 个错误：Line 6: call to undeclared function 'v2_total'; ISO C99 and later do not support implicit function declarations
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
