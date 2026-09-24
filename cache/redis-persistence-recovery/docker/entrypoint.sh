#!/bin/sh
# 挂载 LazyFS：/data 是 Redis 的工作目录，真正落盘的内容在 /lazyfs-root。Redis 由实验脚本通过 docker exec 启停
set -e
mkdir -p /data /lazyfs-root
/opt/lazyfs/lazyfs/build/lazyfs /data --config-path /etc/lazyfs.toml -o allow_other -o modules=subdir -o subdir=/lazyfs-root -f -s &
for i in $(seq 50); do grep -q ' /data fuse' /proc/mounts && break; sleep 0.1; done
grep -q ' /data fuse' /proc/mounts || { echo "LazyFS 挂载失败"; exit 1; }
echo "LazyFS mounted"
wait
