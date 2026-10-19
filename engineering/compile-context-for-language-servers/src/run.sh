#!/bin/sh
# 语言服务器看到的编译上下文：没有编译数据库、CMake 生成的数据库、构建改了宏而数据库没更新、换成新数据库（clangd 19、CMake 3.31）
set -eu
cp -r /src/proj /tmp/p; cd /tmp/p
out() { printf '%s\t%s\n' "$1" "$2"; }
# clangd --check 的诊断行形如 E[时间] [代码] Line N: 消息；IncludeCleaner 的内部日志不是诊断，去掉
diags() {
  clangd --check=src/main.c "$@" 2>&1 | sed -n 's/^E\[[^]]*\] \[[^]]*\] \(Line [0-9]*: .*\)/\1/p' > d || true
  n=$(wc -l < d); [ "$n" -eq 0 ] && echo "0 个错误" || echo "$n 个错误：$(paste -sd'|' d)"
}
out env "$(clangd --version | head -1 | sed 's/ (.*//')；$(cmake --version | head -1)"
out none "没有 compile_commands.json：$(diags)"
cmake -S . -B build -DCMAKE_EXPORT_COMPILE_COMMANDS=ON >/dev/null
out cdb.command "CMake 生成的编译命令：$(sed -n 's/.*"command": "\(.*\)",/\1/p' build/compile_commands.json | sed 's|/tmp/p/||g; s|  *| |g')"
out cdb "用 build/ 的编译数据库：$(diags --compile-commands-dir=build)"
cmake -S . -B build -DUSE_V2=ON >/dev/null
if cmake --build build >log 2>&1; then r="成功"; else r="失败：$(grep -m1 -o "error: .*" log)"; fi
out build.v2 "构建改成 -DUSE_V2=ON：cmake --build $r"
cp build/compile_commands.json v2.json
cmake -S . -B build -DUSE_V2=OFF >/dev/null
mkdir -p stale && cp build/compile_commands.json stale/
out stale "编辑器仍指向构建前生成、不含 USE_V2 的数据库：$(diags --compile-commands-dir=stale)"
mkdir -p fresh && cp v2.json fresh/compile_commands.json
out fresh.command "构建后的编译命令：$(sed -n 's/.*"command": "\(.*\)",/\1/p' fresh/compile_commands.json | sed 's|/tmp/p/||g; s|  *| |g')"
out fresh "换成与构建一致的数据库：$(diags --compile-commands-dir=fresh)"
