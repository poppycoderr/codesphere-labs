# 被其他脚本 source：统一的 compose 与 SQL 执行入口
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ../../shared/scripts/lib.sh
require docker python3
COMPOSE=(docker compose -f compose.yaml)
# sql [mysql 参数...]：从标准输入读取 SQL，在容器内执行（演示密码 example_password，只在本地容器中使用）
sql() { "${COMPOSE[@]}" exec -T -e MYSQL_PWD=example_password mysql mysql -uroot labs "$@"; }
