#!/usr/bin/env bash
# 生成 evidence/ 的完整命令，与 make evidence 等价
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/verify.sh evidence
