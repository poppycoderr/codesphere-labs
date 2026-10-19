#!/usr/bin/env bash
# 工作区、暂存区与提交：按块暂存、暂存后再修改、git apply --cached、restore -p 与 reset --hard 丢掉了什么（Git 2.54）
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
write_environment "$OUT/environment.txt" "git_image: $GIT（v2.54.0）" "git: 2.54.0"
cat "$f" >&2
# 输入固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
hunks	改了第 3 行与第 25 行：git diff 有 2 个块
add.p	git add -p 对两个块依次回答 y、n：暂存区与 HEAD 的差异 1 个块（line 3 fix bug），工作区与暂存区的差异 1 个块（line 25 debug print）
commit	提交后：提交里改了 1 个文件，内容「line 3 fix bug」；工作区仍有「line 25 debug print」
near	改第 10 行与第 12 行（中间只隔 1 行）：git diff 有 1 个块；add -p 里输入 s 拆分后：Split into 2 hunks
stage.then.edit	暂存「line 5 v1」后又改成 v2 再提交：提交里是「line 5 v1」，工作区是「line 5 v2」
apply.cached	只把第一个块的补丁交给 git apply --cached（编辑器 Git 插件按块暂存的做法）：暂存区 1 个块，工作区 1 个块
restore.p	git restore -p 丢弃第一个块：第 7 行变回「line 7」，第 28 行仍是「line 28 drop」；这个修改从未暂存或提交，Git 里没有任何副本
reset.hard	暂存一处、未暂存一处后 git reset --hard：第 15 行「line 15」，第 16 行「line 16」
fsck	git fsck --lost-found 找回的悬空对象里：「line 15 staged fix」；已暂存的内容在 add 时写成了对象，从未暂存的修改找不回
stash	先 git stash 再 git reset --hard，stash pop 后第 20 行：「line 20 wip」
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
