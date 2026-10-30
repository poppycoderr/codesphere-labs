#!/bin/sh
# reset、restore、revert、checkout 各动了 HEAD、索引、工作树中的哪几个；reflog、stash、.gitignore 的边界。每个场景输出一行。
set -eu
export GIT_AUTHOR_DATE="2026-01-01T00:00:00Z" GIT_COMMITTER_DATE="2026-01-01T00:00:00Z"
git config --global user.name lab
git config --global user.email lab@example.com
git config --global init.defaultBranch main
git config --global advice.detachedHead false
o() { printf '%s\t%s\n' "$1" "$2"; }

base() {
  rm -rf /tmp/base && mkdir /tmp/base && cd /tmp/base && git init -q
  echo v1 > f && git add f && git commit -qm A
  echo v2 > f && git add f && git commit -qm B
  echo v3 > f && git add f                                   # 已暂存，未提交
  echo v4 > f                                                # 工作树里又改了一次
}

state() {
  printf 'HEAD=%s 索引=%s 工作树=%s' "$(git log -1 --format=%s)" "$(git show :f 2>/dev/null || echo 无)" "$(cat f)"
}

base
o "start" "起点：提交 A(v1)、B(v2)；索引里暂存了 v3，工作树是 v4 → $(state)"
for cmd in "reset --soft HEAD~1" "reset HEAD~1" "reset --hard HEAD~1" "restore f" "restore --staged f" "restore --source=HEAD~1 --staged --worktree f" "checkout -- f" "checkout HEAD~1 -- f" "stash"; do
  rm -rf /tmp/w && cp -r /tmp/base /tmp/w && cd /tmp/w
  git $cmd >/dev/null 2>&1
  o "m.$(echo "$cmd" | tr ' =~/' '____')" "git $cmd → $(state)"
done
rm -rf /tmp/w && cp -r /tmp/base /tmp/w && cd /tmp/w
git commit -qm C
dirty=$(git revert --no-edit HEAD 2>&1 | grep -c "would be overwritten" || true)
git restore f
git revert --no-edit HEAD >/dev/null
o "revert.dirty" "工作树里还有未提交的 v4 时 git revert HEAD：拒绝执行（提示本地修改会被覆盖）=$([ "$dirty" -ge 1 ] && echo true || echo false)"
o "revert" "提交 v3 为 C、丢弃工作树修改后 git revert HEAD：新增一个提交「$(git log -1 --format=%s)」，提交数 $(git rev-list --count HEAD)，工作树 $(cat f)；历史没有被改写"

# reset --hard 丢掉未提交的修改；reflog 只能找回提交过的内容
rm -rf /tmp/w && cp -r /tmp/base /tmp/w && cd /tmp/w
git reset -q --hard HEAD~1
lost=$(git log -g --format=%gs | head -3 | tr '\n' '|')
git reset -q --hard 'HEAD@{1}'
o "reflog.commit" "reset --hard HEAD~1 之后用 reset --hard HEAD@{1} 回到 B：HEAD=$(git log -1 --format=%s)；工作树里的 v4 找回=$( [ "$(cat f)" = v4 ] && echo true || echo false)"
o "reflog.entries" "reflog 最近三条：$lost"

# reflog 是本地的，也会过期
rm -rf /tmp/w && cp -r /tmp/base /tmp/w && cd /tmp/w
git commit -qm C
c=$(git rev-parse HEAD)
git reset -q --hard HEAD~1
git clone -q --no-local /tmp/w /tmp/clone
o "reflog.local" "克隆出来的仓库 reflog 只有 $(git -C /tmp/clone reflog | wc -l | tr -d ' ') 条（克隆本身）；按正常传输协议克隆（--no-local），被 reset 掉的 C 在克隆里存在=$(git -C /tmp/clone cat-file -e "$c" 2>/dev/null && echo true || echo false)"
o "reflog.before-gc" "原仓库里被 reset 掉的 C 仍可读=$(git cat-file -e "$c" 2>/dev/null && echo true || echo false)；gc.reflogExpire 配置为「$(git config gc.reflogExpire || echo 未设置)」，gc.reflogExpireUnreachable 为「$(git config gc.reflogExpireUnreachable || echo 未设置)」"
git reflog expire --expire-unreachable=now --all
git gc -q --prune=now
o "reflog.gc" "立即让不可达的 reflog 过期并 gc --prune=now 之后：C 仍可读=$(git cat-file -e "$c" 2>/dev/null && echo true || echo false)"

# stash 与未跟踪、被忽略的文件
rm -rf /tmp/w && mkdir /tmp/w && cd /tmp/w && git init -q
echo '*.log' > .gitignore && echo a > t && git add . && git commit -qm init
echo b > t; echo new > untracked.txt; echo x > debug.log
git stash -q
s1="tracked=$(cat t) untracked=$([ -f untracked.txt ] && echo 还在 || echo 收走) ignored=$([ -f debug.log ] && echo 还在 || echo 收走)"
git stash pop -q
git stash -q -u
s2="untracked=$([ -f untracked.txt ] && echo 还在 || echo 收走) ignored=$([ -f debug.log ] && echo 还在 || echo 收走)"
git stash pop -q
git stash -q -a
s3="ignored=$([ -f debug.log ] && echo 还在 || echo 收走)"
git stash pop -q
o "stash" "git stash 之后 $s1；stash -u 之后 $s2；stash -a 之后 $s3"

# .gitignore 不会让已跟踪的文件停止跟踪
rm -rf /tmp/w && mkdir /tmp/w && cd /tmp/w && git init -q
echo 1 > app.env && git add app.env && git commit -qm init
echo 'app.env' > .gitignore && git add .gitignore && git commit -qm ignore
echo 2 > app.env
o "ignore.tracked" "app.env 已被跟踪后写进 .gitignore，再修改它：git status 显示「$(git status --porcelain app.env)」；git check-ignore 认为它被忽略=$(git check-ignore -q app.env && echo true || echo false)；git rm --cached 后：「$(git rm -q --cached app.env && git status --porcelain app.env)」"

o "env" "git $(git --version | cut -d' ' -f3)"
