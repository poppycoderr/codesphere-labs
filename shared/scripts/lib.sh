#!/usr/bin/env bash
# 各实验脚本共用的函数。用法：source "$(git rev-parse --show-toplevel)/shared/scripts/lib.sh"
set -euo pipefail

LABS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LABS_CACHE="${LABS_CACHE:-$LABS_ROOT/.cache}"

log()  { printf '\033[36m[labs]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[31m[labs] 失败：\033[0m%s\n' "$*" >&2; exit 1; }

# require <命令>...：缺少依赖时直接失败
require() {
  local c
  for c in "$@"; do command -v "$c" >/dev/null 2>&1 || fail "缺少命令 $c"; done
}

# require_java <最低主版本>
require_java() {
  require java javac
  local v
  v=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
  [ "${v%%.*}" -ge "$1" ] || fail "需要 JDK $1 及以上，当前 $v"
}

# junit_jar：输出 JUnit Platform Console Standalone 的路径，首次使用时下载并校验
JUNIT_VERSION=1.13.4
JUNIT_SHA1=62734191af1bfc78ee17181ca38b94dbb5eb7737
junit_jar() {
  local jar="$LABS_CACHE/junit-platform-console-standalone-$JUNIT_VERSION.jar"
  if [ ! -f "$jar" ]; then
    mkdir -p "$LABS_CACHE"
    log "下载 JUnit Platform Console Standalone $JUNIT_VERSION"
    curl -fsSL -o "$jar.tmp" "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"
    local got
    got=$(shasum -a 1 "$jar.tmp" 2>/dev/null | cut -d' ' -f1 || sha1sum "$jar.tmp" | cut -d' ' -f1)
    [ "$got" = "$JUNIT_SHA1" ] || { rm -f "$jar.tmp"; fail "JUnit jar 校验失败：$got"; }
    mv "$jar.tmp" "$jar"
  fi
  echo "$jar"
}

# maven_jar <group:artifact:version>：从 Maven Central 下载单个 jar 到缓存目录并输出路径
maven_jar() {
  local g a v
  IFS=: read -r g a v <<<"$1"
  local jar="$LABS_CACHE/m2/$a-$v.jar"
  if [ ! -f "$jar" ]; then
    mkdir -p "$LABS_CACHE/m2"
    curl -fsSL -o "$jar" "https://repo1.maven.org/maven2/${g//.//}/$a/$v/$a-$v.jar" || fail "下载 $1 失败"
  fi
  echo "$jar"
}

# write_environment <输出文件> [组件说明...]：记录运行环境，不写主机名、用户名和绝对路径
write_environment() {
  local out="$1"; shift
  {
    echo "# 由 shared/scripts/lib.sh write_environment 生成"
    echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "os: $(uname -s) $(uname -r)"
    echo "arch: $(uname -m)"
    if [ "$(uname -s)" = Darwin ]; then
      echo "os_version: macOS $(sw_vers -productVersion)"
      echo "cpus: $(sysctl -n hw.ncpu)"
      echo "memory_gb: $(( $(sysctl -n hw.memsize) / 1073741824 ))"
    else
      echo "cpus: $(nproc)"
      echo "memory_gb: $(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1048576 ))"
    fi
    if command -v java >/dev/null 2>&1; then
      echo "java: $(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.runtime.version/ {print $2}') ($(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.vendor =/ {print $2}'))"
    fi
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
      echo "docker_server: $(docker version --format '{{.Server.Version}}')"
    fi
    local extra
    for extra in "$@"; do echo "$extra"; done
  } >"$out"
}

# normalize_paths：把输出中的仓库绝对路径替换为 /workspace
normalize_paths() { sed -e "s#$LABS_ROOT#/workspace#g" -e "s#$HOME#~#g" -e "s#started by $(id -un) in#started by <user> in#g"; }

# expect_line <文件> <固定字符串> [说明]：断言证据中出现某一行
expect_line() {
  grep -qF -- "$2" "$1" || fail "${3:-断言失败}：$1 中没有「$2」"
  log "通过：${3:-$2}"
}

# ---------- 轻量实验共用的 MySQL 8.4.11（shared/docker/mysql84） ----------
JDK_IMAGE="eclipse-temurin:21-jdk@sha256:78ab9771b4650066c3ef748d46e05dbd6094d8bb34e0667a074486812efd655b"
MYSQL_JDBC_JAR_COORD="mysql:mysql-connector-java:8.0.27"

# mysql_up <项目名>：启动并等待健康，输出容器 ID
mysql_up() {
  require docker
  docker compose -p "$1" -f "$LABS_ROOT/shared/docker/mysql84/compose.yaml" up -d --wait >&2
  docker compose -p "$1" -f "$LABS_ROOT/shared/docker/mysql84/compose.yaml" ps -q mysql
}
# mysql_down <项目名>：删除该项目的容器、网络与匿名卷
mysql_down() { docker compose -p "$1" -f "$LABS_ROOT/shared/docker/mysql84/compose.yaml" down -v --remove-orphans; }
# mysql_exec <项目名> [mysql 参数...]：从标准输入读取 SQL
mysql_exec() {
  local p="$1"; shift
  docker compose -p "$p" -f "$LABS_ROOT/shared/docker/mysql84/compose.yaml" exec -T -e MYSQL_PWD=example_password mysql mysql -uroot "$@"
}
# java_in_network <容器 ID> [java 参数...]：在与 MySQL 共享网络的 JDK 21 容器中运行，当前目录挂载为 /w，缓存目录挂载为 /cache
java_in_network() {
  local cid="$1"; shift
  docker run --rm --network "container:$cid" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$JDK_IMAGE" java "$@"
}

# expect_regex <文件> <扩展正则> <说明>：断言证据中有匹配的行
expect_regex() {
  grep -qE -- "$2" "$1" || fail "${3:-断言失败}：$1 中没有匹配 /$2/ 的行"
  log "通过：${3:-$2}"
}

# kind 与 Kubernetes 节点镜像：二进制按平台从官方 release 下载并校验 sha256
KIND_VERSION=v0.33.0
KIND_NODE_IMAGE="kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed"
kind_bin() {
  local os arch want bin
  os=$(uname -s | tr '[:upper:]' '[:lower:]')
  arch=$(uname -m); [ "$arch" = x86_64 ] && arch=amd64; [ "$arch" = aarch64 ] && arch=arm64
  case "$os-$arch" in
    darwin-arm64) want=0c8c7dbe5e23594a198b786c4bc13dacc101fa6196b0cb0b23a1ca44e61f4b4f ;;
    darwin-amd64) want=5a99f26f57246dc9319dd294803313197a0f34d33c525b3ea8b655db5916ece0 ;;
    linux-arm64)  want=20022bee6cfcd5086cb7234d218e3454e6090022f2a8f55d1fa7fcf42c3867a2 ;;
    linux-amd64)  want=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d ;;
    *) fail "kind 不支持的平台 $os-$arch" ;;
  esac
  bin="$LABS_CACHE/bin/kind-$KIND_VERSION"
  if [ ! -x "$bin" ]; then
    mkdir -p "$LABS_CACHE/bin"
    log "下载 kind $KIND_VERSION（$os-$arch）"
    curl -fsSL -o "$bin.tmp" "https://github.com/kubernetes-sigs/kind/releases/download/$KIND_VERSION/kind-$os-$arch"
    local got
    got=$(shasum -a 256 "$bin.tmp" 2>/dev/null | cut -d' ' -f1 || sha256sum "$bin.tmp" | cut -d' ' -f1)
    [ "$got" = "$want" ] || { rm -f "$bin.tmp"; fail "kind 校验失败：$got"; }
    chmod +x "$bin.tmp" && mv "$bin.tmp" "$bin"
  fi
  echo "$bin"
}
# kind_up <集群名> [配置文件]：创建集群，不等待节点就绪（关闭默认 CNI 时节点不会就绪）
kind_up() {
  require docker
  local kind; kind=$(kind_bin)
  "$kind" delete cluster --name "$1" >/dev/null 2>&1 || true
  # kind 会把宿主机的代理变量写进节点；指向 127.0.0.1 的代理在节点内不可达，这里不传
  env -u HTTP_PROXY -u HTTPS_PROXY -u NO_PROXY -u http_proxy -u https_proxy -u no_proxy -u ALL_PROXY -u all_proxy \
    "$kind" create cluster --name "$1" --image "$KIND_NODE_IMAGE" ${2:+--config "$2"} >&2
}
kind_down() { "$(kind_bin)" delete cluster --name "$1" >&2; }
# kctl <集群名> [kubectl 参数...]：使用控制面节点内的 kubectl，版本与集群一致，标准输入透传
kctl() {
  local c="$1"; shift
  docker exec -i "$c-control-plane" kubectl --kubeconfig /etc/kubernetes/admin.conf "$@"
}
