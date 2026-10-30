#!/usr/bin/env bash
# reset、restore、revert、checkout 对 HEAD、索引、工作树的影响矩阵；reflog 找回、本地性与过期；stash 与未跟踪、被忽略的文件；.gitignore 不取消跟踪（Git 2.54）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 alpine/git:v2.54.0 容器，仓库建在容器内的临时目录；约 5 秒
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
start	起点：提交 A(v1)、B(v2)；索引里暂存了 v3，工作树是 v4 → HEAD=B 索引=v3 工作树=v4
m.reset_--soft_HEAD_1	git reset --soft HEAD~1 → HEAD=A 索引=v3 工作树=v4
m.reset_HEAD_1	git reset HEAD~1 → HEAD=A 索引=v1 工作树=v4
m.reset_--hard_HEAD_1	git reset --hard HEAD~1 → HEAD=A 索引=v1 工作树=v1
m.restore_f	git restore f → HEAD=B 索引=v3 工作树=v3
m.restore_--staged_f	git restore --staged f → HEAD=B 索引=v2 工作树=v4
m.restore_--source_HEAD_1_--staged_--worktree_f	git restore --source=HEAD~1 --staged --worktree f → HEAD=B 索引=v1 工作树=v1
m.checkout_--_f	git checkout -- f → HEAD=B 索引=v3 工作树=v3
m.checkout_HEAD_1_--_f	git checkout HEAD~1 -- f → HEAD=B 索引=v1 工作树=v1
m.stash	git stash → HEAD=B 索引=v2 工作树=v2
revert.dirty	工作树里还有未提交的 v4 时 git revert HEAD：拒绝执行（提示本地修改会被覆盖）=true
revert	提交 v3 为 C、丢弃工作树修改后 git revert HEAD：新增一个提交「Revert "C"」，提交数 4，工作树 v2；历史没有被改写
reflog.commit	reset --hard HEAD~1 之后用 reset --hard HEAD@{1} 回到 B：HEAD=B；工作树里的 v4 找回=false
reflog.entries	reflog 最近三条：reset: moving to HEAD~1|commit: B|commit (initial): A|
reflog.local	克隆出来的仓库 reflog 只有 1 条（克隆本身）；按正常传输协议克隆（--no-local），被 reset 掉的 C 在克隆里存在=false
reflog.before-gc	原仓库里被 reset 掉的 C 仍可读=true；gc.reflogExpire 配置为「未设置」，gc.reflogExpireUnreachable 为「未设置」
reflog.gc	立即让不可达的 reflog 过期并 gc --prune=now 之后：C 仍可读=false
stash	git stash 之后 tracked=a untracked=还在 ignored=还在；stash -u 之后 untracked=收走 ignored=还在；stash -a 之后 ignored=收走
ignore.tracked	app.env 已被跟踪后写进 .gitignore，再修改它：git status 显示「 M app.env」；git check-ignore 认为它被忽略=false；git rm --cached 后：「D  app.env」
env	git 2.54.0
EXPECTED
log "全部通过，输出在 ${OUT}"
