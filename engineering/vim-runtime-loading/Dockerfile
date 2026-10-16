# Vim 9.1（Debian trixie 的 vim-nox 包，版本固定）
FROM debian@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends vim-nox=2:9.1.1230-2 >/dev/null && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8
