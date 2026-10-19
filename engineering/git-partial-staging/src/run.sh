#!/bin/sh
# 工作区、暂存区与提交：按块暂存、暂存后再修改、git apply --cached（编辑器插件的做法）、restore -p 与 reset --hard 丢掉了什么（Git 2.54）
set -eu
out() { printf '%s\t%s\n' "$1" "$2"; }
cd "$(mktemp -d)"
git init -q -b main repo && cd repo
git config user.name demo && git config user.email demo@example.com && git config advice.detachedHead false
seq 1 30 | sed 's/^/line /' > app.txt && git add app.txt && git commit -qm init
hunks() { git diff "$@" | grep -c '^@@' || true; }

sed -i 's/^line 3$/line 3 fix bug/; s/^line 25$/line 25 debug print/' app.txt
out hunks "改了第 3 行与第 25 行：git diff 有 $(hunks) 个块"
printf 'y\nn\n' | git add -p app.txt >/dev/null 2>&1
out add.p "git add -p 对两个块依次回答 y、n：暂存区与 HEAD 的差异 $(hunks --cached) 个块（$(git diff --cached | grep '^+line' | sed 's/^+//')），工作区与暂存区的差异 $(hunks) 个块（$(git diff | grep '^+line' | sed 's/^+//')）"
git commit -qm 'fix bug'
out commit "提交后：提交里改了 $(git show --stat --format= HEAD | grep -c app.txt) 个文件，内容「$(git show HEAD | grep '^+line' | sed 's/^+//')」；工作区仍有「$(git diff | grep '^+line' | sed 's/^+//')」"
git checkout -q -- app.txt

sed -i 's/^line 10$/line 10 first/; s/^line 12$/line 12 second/' app.txt
out near "改第 10 行与第 12 行（中间只隔 1 行）：git diff 有 $(hunks) 个块；add -p 里输入 s 拆分后：$(printf 's\ny\nn\n' | git add -p app.txt 2>/dev/null | grep -o 'Split into [0-9]* hunks' | head -1)"
git reset -q; git checkout -q -- app.txt

sed -i 's/^line 5$/line 5 v1/' app.txt && git add app.txt
sed -i 's/^line 5 v1$/line 5 v2/' app.txt
git commit -qm 'edit line 5'
out stage.then.edit "暂存「line 5 v1」后又改成 v2 再提交：提交里是「$(git show HEAD:app.txt | sed -n 5p)」，工作区是「$(sed -n 5p app.txt)」"
git checkout -q -- app.txt

sed -i 's/^line 7$/line 7 keep/; s/^line 28$/line 28 drop/' app.txt
git diff > all.patch
awk '/^@@/{n++} n<2' all.patch > first.patch
git apply --cached first.patch
out apply.cached "只把第一个块的补丁交给 git apply --cached（编辑器 Git 插件按块暂存的做法）：暂存区 $(hunks --cached) 个块，工作区 $(hunks) 个块"
git reset -q; rm -f all.patch first.patch

printf 'y\nn\n' | git restore -p app.txt >/dev/null 2>&1
out restore.p "git restore -p 丢弃第一个块：第 7 行变回「$(sed -n 7p app.txt)」，第 28 行仍是「$(sed -n 28p app.txt)」；这个修改从未暂存或提交，Git 里没有任何副本"
git checkout -q -- app.txt

sed -i 's/^line 15$/line 15 staged fix/' app.txt && git add app.txt
sed -i 's/^line 16$/line 16 unstaged work/' app.txt
git reset -q --hard
out reset.hard "暂存一处、未暂存一处后 git reset --hard：第 15 行「$(sed -n 15p app.txt)」，第 16 行「$(sed -n 16p app.txt)」"
found=$(for b in $(git fsck --lost-found 2>/dev/null | awk '/dangling blob/{print $3}'); do git cat-file -p "$b" | grep -E 'staged fix|unstaged work'; done | tr '\n' ' ' | sed 's/ $//')
out fsck "git fsck --lost-found 找回的悬空对象里：「${found:-无}」；已暂存的内容在 add 时写成了对象，从未暂存的修改找不回"

sed -i 's/^line 20$/line 20 wip/' app.txt
git stash -q
git reset -q --hard
git stash pop -q
out stash "先 git stash 再 git reset --hard，stash pop 后第 20 行：「$(sed -n 20p app.txt)」"
