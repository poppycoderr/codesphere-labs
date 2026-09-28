#!/usr/bin/env bash
# Postman 集合在 Newman 下的 token 脚本：常见写法与改进写法在正常、密钥错误时的表现；变量作用域、顶层 await、回调里 throw 三个坑
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 node:24-slim 容器，依赖由 package-lock.json 锁定；约 1 分钟（首次需要下载依赖）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/npm"
NODE_IMAGE="node:24-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
docker run --rm -v "$PWD:/w" -v "$LABS_CACHE/npm:/root/.npm" -w /w "$NODE_IMAGE" sh -c '
  npm ci --no-audit --no-fund --loglevel=error >/dev/null
  node src/run.js > build/output.tsv
  { node --version; node_modules/.bin/newman --version; } > build/versions.txt' || fail "运行失败"
mv build/output.tsv "$OUT/output.tsv"; mv build/versions.txt "$OUT/versions.txt"
write_environment "$OUT/environment.txt" "node_image: $NODE_IMAGE"
f="$OUT/output.tsv"
cat "$f" "$OUT/versions.txt" >&2
expect_line "$OUT/versions.txt" "6.2.2" "Newman 版本"
expect_regex "$f" "^naive\.ok	退出码 0；token 请求 [2-9] 次（成功 [2-9]）；业务请求发出 8 次（200：8，401：0" "常见写法：正常路径 8 个 200"
expect_regex "$f" "^naive\.wrong_secret	.*token 请求 8 次（成功 0）；业务请求发出 8 次（200：0，401：8" "常见写法：密钥错误时业务请求照样发出，收到业务接口的 401"
expect_regex "$f" "^improved\.ok	退出码 0；token 请求 [2-9] 次（成功 [2-9]）；业务请求发出 8 次（200：8，401：0" "改进写法：正常路径 8 个 200"
expect_regex "$f" "^improved\.wrong_secret	退出码 1；token 请求 8 次（成功 0）；业务请求发出 0 次.*断言失败 8" "改进写法：密钥错误时业务请求 0 次，8 条失败断言，退出码 1"
expect_regex "$f" "^pitfall\.collection_scope	.*token 请求 [2-9] 次（成功 [2-9]）；业务请求发出 8 次（200：8" "pm.collectionVariables.get 读不到 --env-var 的覆盖"
expect_regex "$f" "^pitfall\.top_level_await	.*业务请求发出 8 次.*Authorization 为未替换的 \{\{access_token\}\}：8" "顶层 await：脚本报错，请求带着未替换的变量照样发出"
expect_regex "$f" "^pitfall\.top_level_await\.first_failure	SyntaxError" "顶层 await 在 Newman 里是 SyntaxError"
expect_regex "$f" "^pitfall\.throw_in_callback	退出码 0；.*业务请求发出 8 次（200：0，401：8.*断言失败 0；prerequest-scripts 失败 0$" "回调里 throw：不计失败，退出码 0"
log "全部通过，输出在 $OUT"
