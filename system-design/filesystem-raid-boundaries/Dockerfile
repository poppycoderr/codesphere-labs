FROM debian@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f
RUN apt-get update -qq && apt-get install -y --no-install-recommends e2fsprogs=1.47.2-3+b12 libext2fs2t64=1.47.2-3+b12 libcom-err2=1.47.2-3+b12 libss2=1.47.2-3+b12 logsave=1.47.2-3+b12 && rm -rf /var/lib/apt/lists/*
WORKDIR /work
