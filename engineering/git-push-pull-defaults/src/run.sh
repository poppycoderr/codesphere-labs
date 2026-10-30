#!/bin/sh
# 推送与拉取的默认行为：push.default、分歧时的 pull、提交身份、--force-with-lease 与 --force-if-includes、cherry-pick。每个场景输出一行。
set -eu
export GIT_AUTHOR_DATE="2026-01-01T00:00:00Z" GIT_COMMITTER_DATE="2026-01-01T00:00:00Z"
git config --global user.name lab
git config --global user.email lab@example.com
git config --global init.defaultBranch main
git config --global advice.detachedHead false
o() { printf '%s\t%s\n' "$1" "$2"; }
first() { grep -m1 -E "$1" || true; }

setup() {
  rm -rf /tmp/r && mkdir -p /tmp/r && cd /tmp/r
  git init -q --bare origin.git
  git clone -q origin.git alice 2>/dev/null
  cd alice && echo base > f && git add f && git commit -qm base && git push -q origin main && cd ..
  git clone -q origin.git bob
}

setup
o "config" "push.default 配置为「$(git config push.default || echo 未设置)」，pull.rebase 为「$(git config pull.rebase || echo 未设置)」，pull.ff 为「$(git config pull.ff || echo 未设置)」"

# 一、push.default=simple：新分支没有上游时
cd /tmp/r/alice && git switch -q -c feature && echo f1 >> f && git commit -qam f1
o "push.no-upstream" "新分支 feature 没有上游时 git push：$(git push 2>&1 | first 'fatal|has no upstream' | sed 's/^fatal: //')"
git -c push.autoSetupRemote=true push -q 2>/dev/null
o "push.auto-setup" "设 push.autoSetupRemote=true 后 git push：远端 feature 存在=$(git ls-remote -q origin refs/heads/feature | grep -q . && echo true || echo false)，上游为 $(git rev-parse --abbrev-ref '@{u}')"
git switch -q -c local-name && git branch -q --set-upstream-to=origin/feature
o "push.name-mismatch" "本地分支 local-name 的上游是 origin/feature（名字不同）时 git push：$(git push 2>&1 | first 'fatal' | sed 's/^fatal: //')"

# 二、分歧时的 pull
setup
cd /tmp/r/alice && echo a >> f && git commit -qam alice && git push -q
cd /tmp/r/bob && echo b > g && git add g && git commit -qm bob
o "pull.divergent" "两边各有一个新提交，bob 直接 git pull：$(git pull 2>&1 | first 'fatal|hint: You have divergent' | sed 's/^fatal: //')；本地提交数仍是 $(git rev-list --count HEAD)"
o "pull.ff-only" "git pull --ff-only：$(git pull --ff-only 2>&1 | first 'fatal' | sed 's/^fatal: //')"
before=$(git rev-parse --short HEAD)
cp -r /tmp/r/bob /tmp/r/bob2
git pull -q --rebase 2>/dev/null
o "pull.rebase" "git pull --rebase：bob 的提交 $before 变成 $(git rev-parse --short HEAD)（内容相同、身份不同），历史是一条直线，HEAD 的父提交 $(( $(git rev-list --parents -1 HEAD | wc -w) - 1 )) 个"
cd /tmp/r/bob2 && git pull -q --no-rebase --no-edit 2>/dev/null
o "pull.merge" "git pull --no-rebase：新增合并提交，HEAD 的父提交 $(( $(git rev-list --parents -1 HEAD | wc -w) - 1 )) 个；bob 的提交身份不变=$(git merge-base --is-ancestor "$before" HEAD && echo true || echo false)"

# 三、amend 与 cherry-pick 也产生新提交
cd /tmp/r/bob && h1=$(git rev-parse --short HEAD) && git commit -q --amend --no-edit --date="2026-01-01T00:00:00Z" && h2=$(git rev-parse --short HEAD)
o "amend" "提交身份是内容、父提交、作者与提交者信息（含时间）的哈希：实验固定了提交时间，什么都不改的 amend 得到同一个提交=$([ "$h1" = "$h2" ] && echo true || echo false)"
GIT_COMMITTER_DATE="2026-01-02T00:00:00Z" git commit -q --amend --no-edit && h3=$(git rev-parse --short HEAD)
o "amend.time" "提交时间不同（正常使用时总是如此）的 amend：$h2 → $h3"
git switch -q -c side HEAD~1 && GIT_COMMITTER_DATE="2026-01-03T00:00:00Z" git cherry-pick main >/dev/null 2>&1
o "cherry-pick" "cherry-pick 到另一个分支：新提交 $(git rev-parse --short HEAD)，与原提交 $(git rev-parse --short main) 的 patch-id 相同=$( [ "$(git show main | git patch-id | cut -c1-40)" = "$(git show HEAD | git patch-id | cut -c1-40)" ] && echo true || echo false)"

# 四、强制推送
setup
cd /tmp/r/bob && echo b > g && git add g && git commit -qm bob-1 && git push -q
cd /tmp/r/alice && git pull -q && echo a >> f && git commit -qam alice-2 && git push -q
cd /tmp/r/bob && git commit -q --amend -m bob-1-amended
o "force.lease-stale" "alice 推送了 alice-2 之后，bob 没有 fetch，amend 后 --force-with-lease：$(git push --force-with-lease 2>&1 | first 'rejected|stale' | sed 's/^ *//')"
git fetch -q
o "force.lease-after-fetch" "bob 的编辑器在后台 fetch 了一次（远端跟踪分支更新为 alice-2，但没有合并）后 --force-with-lease --dry-run：$(git push --dry-run --force-with-lease 2>&1 | first 'forced update|rejected' | sed 's/^ *//')"
o "force.if-includes" "同样的情况加 --force-if-includes：$(git push --force-with-lease --force-if-includes 2>&1 | first 'rejected|stale|includes' | sed 's/^ *//')"
git push -q --force-with-lease 2>/dev/null
o "force.lost" "不加 --force-if-includes 真的推送后：远端 main 的历史里还有 alice-2=$(git -C /tmp/r/origin.git log --format=%s main | grep -q alice-2 && echo true || echo false)"

o "env" "git $(git --version | cut -d' ' -f3)"
