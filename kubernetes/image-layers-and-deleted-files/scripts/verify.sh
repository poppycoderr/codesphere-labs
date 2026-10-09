#!/usr/bin/env bash
# 镜像层与被删除的文件：在后面的指令里删除文件之后，镜像的大小、各层的内容与运行时看到的文件系统
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：Docker（BuildKit）与宿主机 python3；构建 5 个基于 busybox 的小镜像，结束时删除；约 1 分钟
# 「令牌」是写在脚本里的演示值，不是真实凭据
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
TOKEN=example_token_0123456789
CTX=build/ctx; rm -rf "$CTX"; mkdir -p "$CTX"; cp src/Dockerfile.* "$CTX/"; printf '%s' "$TOKEN" >"$CTX/token.txt"
VARIANTS="leaky same-run multi-stage secret-mount arg"
cleanup() { for v in $VARIANTS; do docker image rm -f "labs-ild:$v" >/dev/null 2>&1 || true; done; rm -rf build/ctx build/tar; }
trap cleanup EXIT
mkdir -p build/tar
f="$OUT/output.tsv"; : >"$f"
for v in $VARIANTS; do
  case "$v" in
    secret-mount) docker build -q --no-cache -f "$CTX/Dockerfile.$v" --secret "id=token,src=$CTX/token.txt" -t "labs-ild:$v" "$CTX" >/dev/null ;;
    arg)          docker build -q --no-cache -f "$CTX/Dockerfile.$v" --build-arg "TOKEN=$TOKEN" -t "labs-ild:$v" "$CTX" >/dev/null ;;
    *)            docker build -q --no-cache -f "$CTX/Dockerfile.$v" -t "labs-ild:$v" "$CTX" >/dev/null ;;
  esac
  size=$(docker image inspect "labs-ild:$v" --format '{{.Size}}')
  printf '%s.size\t镜像大小约 %d MB\n' "$v" "$(( (size + 524288) / 1048576 ))" >>"$f"
  printf '%s.runtime\t容器里：/tmp/token.txt 存在 = %s，/big.bin 存在 = %s，/build.log 的内容 = %s\n' "$v" \
    "$(docker run --rm "labs-ild:$v" sh -c '[ -e /tmp/token.txt ] && echo true || echo false')" \
    "$(docker run --rm "labs-ild:$v" sh -c '[ -e /big.bin ] && echo true || echo false')" \
    "$(docker run --rm "labs-ild:$v" cat /build.log | tr -s ' ' | sed 's/^ //')" >>"$f"
  docker save "labs-ild:$v" -o "build/tar/$v.tar"
  python3 src/inspect_image.py "build/tar/$v.tar" "$TOKEN" | sed "s/^/$v./" >>"$f"
  printf '%s.history\tdocker history 的输出里含有令牌 = %s\n' "$v" "$(docker history --no-trunc "labs-ild:$v" | grep -q "$TOKEN" && echo true || echo false)" >>"$f"
done
write_environment "$OUT/environment.txt" "buildx: $(docker buildx version | awk '{print $2}')" "base_image: busybox@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e"
cat "$f" >&2
# 层的数量、文件、删除标记与是否含有令牌是确定的；大小按 MB 取整：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
leaky.size	镜像大小约 24 MB
leaky.runtime	容器里：/tmp/token.txt 存在 = false，/big.bin 存在 = false，/build.log 的内容 = 24 /tmp/token.txt
leaky.layers	共 6 层，其中基础镜像 1 层
leaky.layer_1	文件 ['tmp/token.txt']	删除标记 无	内容约 0 MB	含有令牌 = true
leaky.layer_2	文件 ['build.log']	删除标记 无	内容约 0 MB	含有令牌 = false
leaky.layer_3	文件 无	删除标记 ['tmp/token.txt']	内容约 0 MB	含有令牌 = false
leaky.layer_4	文件 ['big.bin']	删除标记 无	内容约 20 MB	含有令牌 = false
leaky.layer_5	文件 无	删除标记 ['big.bin']	内容约 0 MB	含有令牌 = false
leaky.config	镜像配置的构建历史里含有令牌 = false
leaky.history	docker history 的输出里含有令牌 = false
same-run.size	镜像大小约 4 MB
same-run.runtime	容器里：/tmp/token.txt 存在 = false，/big.bin 存在 = false，/build.log 的内容 = 24 /tmp/token.txt
same-run.layers	共 4 层，其中基础镜像 1 层
same-run.layer_1	文件 ['tmp/token.txt']	删除标记 无	内容约 0 MB	含有令牌 = true
same-run.layer_2	文件 ['build.log']	删除标记 ['tmp/token.txt']	内容约 0 MB	含有令牌 = false
same-run.layer_3	文件 无	删除标记 无	内容约 0 MB	含有令牌 = false
same-run.config	镜像配置的构建历史里含有令牌 = false
same-run.history	docker history 的输出里含有令牌 = false
multi-stage.size	镜像大小约 4 MB
multi-stage.runtime	容器里：/tmp/token.txt 存在 = false，/big.bin 存在 = false，/build.log 的内容 = 24 /tmp/token.txt
multi-stage.layers	共 2 层，其中基础镜像 1 层
multi-stage.layer_1	文件 ['build.log']	删除标记 无	内容约 0 MB	含有令牌 = false
multi-stage.config	镜像配置的构建历史里含有令牌 = false
multi-stage.history	docker history 的输出里含有令牌 = false
secret-mount.size	镜像大小约 4 MB
secret-mount.runtime	容器里：/tmp/token.txt 存在 = false，/big.bin 存在 = false，/build.log 的内容 = 24 /run/secrets/token
secret-mount.layers	共 2 层，其中基础镜像 1 层
secret-mount.layer_1	文件 ['build.log']	删除标记 无	内容约 0 MB	含有令牌 = false
secret-mount.config	镜像配置的构建历史里含有令牌 = false
secret-mount.history	docker history 的输出里含有令牌 = false
arg.size	镜像大小约 4 MB
arg.runtime	容器里：/tmp/token.txt 存在 = false，/big.bin 存在 = false，/build.log 的内容 = 25
arg.layers	共 2 层，其中基础镜像 1 层
arg.layer_1	文件 ['build.log']	删除标记 无	内容约 0 MB	含有令牌 = false
arg.config	镜像配置的构建历史里含有令牌 = true
arg.history	docker history 的输出里含有令牌 = true
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
