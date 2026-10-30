#!/usr/bin/env bash
# 推送与拉取的默认行为：push.default、分歧时的 pull、rebase 与 amend 的新提交、--force-with-lease 与 --force-if-includes、cherry-pick（Git 2.54）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 alpine/git:v2.54.0 容器，裸仓库与两个克隆建在容器内的临时目录；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
GIT="alpine/git@sha256:03228d7ded483b0b3e55e1de7fa660d70d3e1bb432fa7021d29e192640de11aa"
f="$OUT/output.tsv"
docker run --rm -v "$PWD/src:/src:ro" --entrypoint sh "$GIT" /src/run.sh >"$f" 2>/dev/null
write_environment "$OUT/environment.txt" "git_image: ${GIT}（v2.54.0）"
cat "$f" >&2
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
config	push.default 配置为「未设置」，pull.rebase 为「未设置」，pull.ff 为「未设置」
push.no-upstream	新分支 feature 没有上游时 git push：The current branch feature has no upstream branch.
push.auto-setup	设 push.autoSetupRemote=true 后 git push：远端 feature 存在=true，上游为 origin/feature
push.name-mismatch	本地分支 local-name 的上游是 origin/feature（名字不同）时 git push：The upstream branch of your current branch does not match
pull.divergent	两边各有一个新提交，bob 直接 git pull：hint: You have divergent branches and need to specify how to reconcile them.；本地提交数仍是 2
pull.ff-only	git pull --ff-only：Not possible to fast-forward, aborting.
pull.rebase	git pull --rebase：bob 的提交 7f17b30 变成 f2f8ed1（内容相同、身份不同），历史是一条直线，HEAD 的父提交 1 个
pull.merge	git pull --no-rebase：新增合并提交，HEAD 的父提交 2 个；bob 的提交身份不变=true
amend	提交身份是内容、父提交、作者与提交者信息（含时间）的哈希：实验固定了提交时间，什么都不改的 amend 得到同一个提交=true
amend.time	提交时间不同（正常使用时总是如此）的 amend：f2f8ed1 → 46af3f7
cherry-pick	cherry-pick 到另一个分支：新提交 1ec3630，与原提交 46af3f7 的 patch-id 相同=true
force.lease-stale	alice 推送了 alice-2 之后，bob 没有 fetch，amend 后 --force-with-lease：! [rejected]        main -> main (stale info)
force.lease-after-fetch	bob 的编辑器在后台 fetch 了一次（远端跟踪分支更新为 alice-2，但没有合并）后 --force-with-lease --dry-run：+ 7047d39...79b41d1 main -> main (forced update)
force.if-includes	同样的情况加 --force-if-includes：! [rejected]        main -> main (remote ref updated since checkout)
force.lost	不加 --force-if-includes 真的推送后：远端 main 的历史里还有 alice-2=false
env	git 2.54.0
EXPECTED
log "全部通过，输出在 ${OUT}"
