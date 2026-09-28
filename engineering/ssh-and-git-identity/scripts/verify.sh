#!/usr/bin/env bash
# GitHub SSH 与多账号：Ed25519 与 RSA 公钥大小、私钥权限、ssh -G 下 IdentityFile 的累加、按目录切换 Git 身份与远程地址
# 全部离线：密钥生成在 build/tmp，不连接 GitHub；证据只记录大小、权限与配置解析结果，不含任何密钥内容
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require ssh ssh-keygen git
OUT="${1:-build/run}"; mkdir -p "$OUT"
T="$PWD/build/tmp"; rm -rf "$T"; mkdir -p "$T/home/.ssh" "$T/home/code/work" "$T/home/code/personal"
f="$OUT/output.tsv"; : >"$f"
out() { printf '%s\t%s\n' "$1" "$2" >>"$f"; }
join() { awk 'NR > 1 { printf "；" } { printf "%s", $0 }'; }

# 1. 公钥大小与私钥权限（注释 macbook-2026，无口令，仅用于测量）
ssh-keygen -q -t ed25519 -C "macbook-2026" -N "" -f "$T/id_ed25519"
ssh-keygen -q -t rsa -b 4096 -C "macbook-2026" -N "" -f "$T/id_rsa"
out key.ed25519 "Ed25519 公钥 $(wc -c <"$T/id_ed25519.pub" | tr -d ' ') 字节，私钥权限 $(stat -f %Lp "$T/id_ed25519" 2>/dev/null || stat -c %a "$T/id_ed25519")"
out key.rsa4096 "RSA 4096 公钥 $(wc -c <"$T/id_rsa.pub" | tr -d ' ') 字节"

# 2. ssh -G：Host * 在前时 IdentityFile 累加；每个别名一个密钥时只剩一个
cat >"$T/bad_config" <<'CFG'
Host *
  IdentityFile ~/.ssh/id_ed25519_personal

Host github-work
  HostName github.com
  IdentityFile ~/.ssh/id_ed25519_work
CFG
cat >"$T/good_config" <<'CFG'
Host github.com
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519_personal
  IdentitiesOnly yes

Host github-work
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519_work
  IdentitiesOnly yes
CFG
out ssh.bad "Host * 在前：$(ssh -G -F "$T/bad_config" github-work 2>/dev/null | grep '^identityfile ' | join)"
out ssh.good "每个别名一个密钥：$(ssh -G -F "$T/good_config" github-work 2>/dev/null | grep -E '^(identityfile|hostname|identitiesonly) ' | sort | join)"

# 3. Git 按目录切换身份与远程地址（临时 HOME，GIT_SSH_COMMAND 只打印要连接的主机）
H="$T/home"
cat >"$H/.gitconfig" <<'CFG'
[user]
  name = Your Name
  email = me@personal.example

[includeIf "gitdir:~/code/work/"]
  path = ~/.gitconfig-work
CFG
cat >"$H/.gitconfig-work" <<'CFG'
[user]
  email = me@work.example

[url "git@github-work:"]
  insteadOf = git@github.com:
CFG
cat >"$T/fake-ssh" <<'SH'
#!/bin/sh
for a in "$@"; do case "$a" in git@*) echo "SSH_HOST=$a" >&2;; esac; done
exit 1
SH
chmod +x "$T/fake-ssh"
g() { HOME="$H" GIT_CONFIG_NOSYSTEM=1 GIT_CEILING_DIRECTORIES="$H" GIT_SSH_COMMAND="$T/fake-ssh" git "$@"; }
g init -q "$H/code/work/order-service"
g init -q "$H/code/personal/blog"
g -C "$H/code/work/order-service" remote add origin git@github.com:acme/order-service.git
g -C "$H/code/personal/blog" remote add origin git@github.com:me/blog.git
out git.work "~/code/work/order-service：user.email=$(g -C "$H/code/work/order-service" config user.email)，远程 $(g -C "$H/code/work/order-service" remote get-url origin)"
out git.personal "~/code/personal/blog：user.email=$(g -C "$H/code/personal/blog" config user.email)，远程 $(g -C "$H/code/personal/blog" remote get-url origin)"
host=$(cd "$H/code/work" && { g clone -q git@github.com:acme/payment.git 2>&1 || true; } | grep -o 'SSH_HOST=[^ ]*' | head -1 | cut -d= -f2)
out git.clone "在 ~/code/work/ 下 clone git@github.com:acme/payment.git：ssh 连接的是 $host"
out git.nonrepo "在 ~/code/work/ 下、还不是仓库的目录里：user.email=$(cd "$H/code/work" && g config user.email || true)"
rm -rf "$T"
write_environment "$OUT/environment.txt" "$(ssh -V 2>&1)" "$(git --version)"
cat "$f" >&2
expect_line "$f" "key.ed25519	Ed25519 公钥 94 字节，私钥权限 600" "Ed25519 公钥大小与私钥权限"
expect_line "$f" "key.rsa4096	RSA 4096 公钥 738 字节" "RSA 4096 公钥大小"
expect_line "$f" "ssh.bad	Host * 在前：identityfile ~/.ssh/id_ed25519_personal；identityfile ~/.ssh/id_ed25519_work" "IdentityFile 累加，个人密钥排在前面"
expect_line "$f" "ssh.good	每个别名一个密钥：hostname github.com；identitiesonly yes；identityfile ~/.ssh/id_ed25519_work" "每个别名只剩一个密钥"
expect_line "$f" "git.work	~/code/work/order-service：user.email=me@work.example，远程 git@github-work:acme/order-service.git" "工作目录：工作邮箱与别名"
expect_line "$f" "git.personal	~/code/personal/blog：user.email=me@personal.example，远程 git@github.com:me/blog.git" "个人目录：个人邮箱与原地址"
expect_line "$f" "git.clone	在 ~/code/work/ 下 clone git@github.com:acme/payment.git：ssh 连接的是 git@github-work" "在工作目录下 clone 也走别名"
expect_line "$f" "git.nonrepo	在 ~/code/work/ 下、还不是仓库的目录里：user.email=me@personal.example" "不是仓库的目录里仍读个人配置"
log "全部通过，输出在 $OUT"
