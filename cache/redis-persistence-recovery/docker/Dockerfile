# Redis 8.10.1 加 LazyFS（固定 commit）。LazyFS 是一个 FUSE 文件系统，未 fsync 的写入只留在它自己的页缓存里，
# 收到 lazyfs::clear-cache 时丢弃，用来模拟操作系统崩溃或断电时丢失 page cache
FROM redis:8.10.1@sha256:8a1efc5f479551822b47424ccae982026b633f28818eab0387348120a61e10e2
ARG LAZYFS_COMMIT=fa7d32ee9710d55a7e529ac3c375f6616563c78b
RUN apt-get update && apt-get install -y --no-install-recommends g++ cmake make git ca-certificates libfuse3-dev fuse3 procps \
 && git clone https://github.com/dsrhaslab/lazyfs.git /opt/lazyfs && cd /opt/lazyfs && git checkout "$LAZYFS_COMMIT" \
 && cd libs/libpcache && ./build.sh && cd ../../lazyfs && ./build.sh \
 && echo user_allow_other >> /etc/fuse.conf && rm -rf /var/lib/apt/lists/*
COPY lazyfs.toml /etc/lazyfs.toml
COPY entrypoint.sh /usr/local/bin/lazyfs-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/lazyfs-entrypoint.sh"]
