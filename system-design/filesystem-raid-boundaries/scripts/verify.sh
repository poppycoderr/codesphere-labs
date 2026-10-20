#!/usr/bin/env bash
# 未挂载的 ext4 镜像：extent 映射、Blockcount 单位、导出校验、删除后与独立早先副本的对比（e2fsprogs 1.47.2）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：基于固定 digest 的 Debian trixie 构建镜像；容器去掉全部 capabilities、无网络、不挂载；约 30 秒（首次构建需下载软件包）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" build; find "$OUT" -mindepth 1 ! -name README.md -delete
docker build -t csl-filesystem-extents:local . >build/image.log 2>&1
docker run --rm --network=none --cpus=1 --memory=256m --cap-drop=ALL --security-opt=no-new-privileges -v "$PWD/src:/src:ro" csl-filesystem-extents:local bash /src/check.sh >"$OUT/ext4.txt"
expect_line "$OUT/ext4.txt" 'roundtrip=true snapshot_roundtrip=true'
expect_regex "$OUT/ext4.txt" 'Flags: 0x80000'
expect_regex "$OUT/ext4.txt" 'EXTENTS:'
expect_regex "$OUT/ext4.txt" 'Size: 51200'
expect_regex "$OUT/ext4.txt" 'Blockcount: 104'
expect_regex "$OUT/ext4.txt" '\(0-12\):'
expect_regex "$OUT/ext4.txt" 'Block size: +4096' 
expect_regex "$OUT/ext4.txt" 'File not found by ext2_lookup'
write_environment "$OUT/environment.txt" 'e2fsprogs: 1.47.2-3+b12' 'base: debian@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f'
cat "$OUT/ext4.txt"
