#!/usr/bin/env bash
# 提交前的敏感信息扫描：个人绝对路径、私钥、常见 Token 格式、非演示密码。命中即失败。
set -euo pipefail
cd "$(dirname "$0")/../.."
patterns=(
  '/Users/[A-Za-z0-9_.-]+'
  '/home/[a-z][A-Za-z0-9_.-]+'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'gh[pousr]_[A-Za-z0-9]{30,}'
  'AKIA[0-9A-Z]{16}'
  'xox[baprs]-[A-Za-z0-9-]{10,}'
  '(password|passwd|pwd)[=:][^ &"]*'
)
allow='example_password|\$\{|\$[A-Z_]+|password=\$|MYSQL_PWD=example_password|<password>'
found=0
for p in "${patterns[@]}"; do
  hits=$(git ls-files -z | xargs -0 grep -nEI -- "$p" 2>/dev/null | grep -vE -- "$allow" | grep -v '^shared/scripts/scan-secrets.sh:' || true)
  if [ -n "$hits" ]; then echo "命中 /$p/："; echo "$hits"; found=1; fi
done
[ "$found" = 0 ] && echo "敏感信息扫描通过（$(git ls-files | wc -l | tr -d ' ') 个已跟踪文件）"
exit "$found"
