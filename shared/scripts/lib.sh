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
