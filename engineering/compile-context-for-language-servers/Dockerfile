# clangd 19、clang 19 与 CMake 3.31（Debian trixie 的包，版本固定）
FROM debian@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends clangd=1:19.0-63 clang=1:19.0-63 cmake=3.31.6-2 make=4.4.1-2 libc6-dev >/dev/null && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8 CC=clang
