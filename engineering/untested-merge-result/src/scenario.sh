#!/usr/bin/env bash
# 在一个临时仓库里依次构造场景，把每一步的结论写到标准输出（键、事实，制表符分隔）。
# 用法：scenario.sh <工作目录>。身份与时间固定，输出不含提交哈希与路径。
set -euo pipefail
W="$1"; rm -rf "$W"; mkdir -p "$W"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=lab GIT_AUTHOR_EMAIL=lab@example.com GIT_COMMITTER_NAME=lab GIT_COMMITTER_EMAIL=lab@example.com
export GIT_AUTHOR_DATE="2026-10-10T00:00:00Z" GIT_COMMITTER_DATE="2026-10-10T00:00:00Z"
out() { printf '%s\t%s\n' "$1" "$2"; }
g() { git -C "$R" -c init.defaultBranch=main -c advice.detachedHead=false "$@"; }
# check <目录>：运行这个目录里的全部测试，输出 通过 或 失败（附最后一行错误）
check() {
  local log; log=$(cd "$1" && python3 -B -m unittest -q 2>&1) && echo "通过" || echo "失败（$(echo "$log" | grep -E 'Error' | tail -1)）"
}
commit() { g add -A; g commit -q -m "$1"; }

# ---------- 一、两个分支各自通过，合并后的提交失败 ----------
R="$W/skew"; mkdir -p "$R"; g init -q
cat >"$R/pricing.py" <<'PY'
def total(price, qty):
    return price * qty
PY
cat >"$R/checkout.py" <<'PY'
import pricing

def checkout(price, qty):
    return pricing.total(price, qty)
PY
cat >"$R/test_checkout.py" <<'PY'
import unittest, checkout

class T(unittest.TestCase):
    def test_checkout(self):
        self.assertEqual(checkout.checkout(10, 2), 20)
PY
commit "initial"
# 分支 rename：把 total 改名为 subtotal，并改掉当时所有的调用方
g switch -q -c rename
sed -i.bak 's/def total/def subtotal/' "$R/pricing.py"; sed -i.bak 's/pricing.total/pricing.subtotal/' "$R/checkout.py"; rm -f "$R"/*.bak
commit "rename total to subtotal"
out "skew.rename_branch" "分支 rename（把 total 改名为 subtotal 并改掉全部调用方）上的检查：$(check "$R")"
# 分支 invoice：从同一个起点出发，新增一个调用 total 的文件
g switch -q main; g switch -q -c invoice
cat >"$R/invoice.py" <<'PY'
import pricing

def invoice_line(price, qty):
    return "total=%d" % pricing.total(price, qty)
PY
cat >"$R/test_invoice.py" <<'PY'
import unittest, invoice

class T(unittest.TestCase):
    def test_invoice(self):
        self.assertEqual(invoice.invoice_line(10, 2), "total=20")
PY
commit "add invoice"
out "skew.invoice_branch" "分支 invoice（新增一个调用 total 的文件）上的检查：$(check "$R")"
# 合并前：用 merge-tree 预先算出合并结果，在临时目录里检查它
g switch -q main; g merge -q --no-ff -m "merge rename" rename
tree=$(g merge-tree --write-tree main invoice) && conflict="无文本冲突" || conflict="有文本冲突"
mkdir -p "$W/speculative"; g archive "$tree" | tar -x -C "$W/speculative"
out "skew.merge_tree" "main 已合入 rename；预先计算 main 与 invoice 的合并结果：$conflict；在这个结果上运行检查：$(check "$W/speculative")"
# 「分支必须先跟上目标分支」：把 main 合进 invoice，再在分支上检查
g switch -q invoice; g merge -q --no-ff -m "update branch" main
out "skew.up_to_date_branch" "要求分支先合入最新的 main：invoice 分支更新后的检查：$(check "$R")"
g reset -q --hard HEAD~1
# 不做以上任何一步，直接合并
g switch -q main
msg=$(g merge --no-ff -m "merge invoice" invoice 2>&1 | grep -c CONFLICT || true)
out "skew.merged_main" "直接合并 invoice：文本冲突 $msg 处，合并提交已生成；main 上的检查：$(check "$R")"

# ---------- 二、撤销一个合并提交之后再次合并同一个分支 ----------
R="$W/revert"; mkdir -p "$R"; g init -q
echo "base" >"$R/app.txt"; commit "initial"
g switch -q -c feature
echo "feature part 1" >"$R/feature.txt"; commit "feature part 1"
echo "feature part 2" >>"$R/feature.txt"; commit "feature part 2"
g switch -q main; g merge -q --no-ff -m "merge feature" feature
out "revert.after_merge" "合并 feature 之后 main 上的 feature.txt：$(tr '\n' '|' <"$R/feature.txt")"
g revert -m 1 --no-edit HEAD >/dev/null
out "revert.after_revert" "撤销这个合并提交之后 feature.txt 存在 = $([ -e "$R/feature.txt" ] && echo true || echo false)"
out "revert.remerge_unchanged" "feature 分支没有新提交时再次合并：$(g merge --no-ff -m again feature 2>&1 | head -1)"
# 在 feature 上追加一个修复提交（改的是另一个文件），再合并一次
g switch -q feature; echo "fix for part 1" >"$R/feature_fix.txt"; commit "feature fix"
g switch -q main; before=$(g rev-parse HEAD)
g merge -q --no-ff -m "merge feature again" feature
out "revert.remerge_with_fix" "feature 追加一个修复提交（新文件 feature_fix.txt）后再次合并：成功；main 上 feature_fix.txt 存在 = $([ -e "$R/feature_fix.txt" ] && echo true || echo false)，feature.txt 存在 = $([ -e "$R/feature.txt" ] && echo true || echo false)"
g reset -q --hard "$before"
# 修复提交改的是 feature.txt 本身时
g switch -q feature; echo "feature part 3" >>"$R/feature.txt"; commit "feature part 3"
g switch -q main
if g merge -q --no-ff -m "merge feature again" feature >/dev/null 2>&1; then
  out "revert.remerge_same_file" "feature 再追加一个修改 feature.txt 的提交后合并：成功"
else
  out "revert.remerge_same_file" "feature 再追加一个修改 feature.txt 的提交后合并：冲突（$(g status --short | grep -v '^A ' | tr '\n' ' ' | sed 's/ *$//')：main 认为这个文件已删除，feature 认为它被修改）"
  g merge --abort
fi
# 正确的做法：先撤销那次撤销，再合并
revert_commit=$(g log --format=%H --grep='^Revert "merge feature"' -1)
g revert --no-edit "$revert_commit" >/dev/null
g merge -q --no-ff -m "merge feature again" feature
out "revert.revert_the_revert" "先撤销那次撤销，再合并 feature：feature.txt：$(tr '\n' '|' <"$R/feature.txt")；feature_fix.txt 存在 = $([ -e "$R/feature_fix.txt" ] && echo true || echo false)"

# ---------- 三、压缩合并之后继续在原分支上开发 ----------
R="$W/squash"; mkdir -p "$R"; g init -q
printf 'line 1\nline 2\nline 3\n' >"$R/notes.txt"; commit "initial"
g switch -q -c topic
sed -i.bak 's/line 2/line 2 edited on topic/' "$R/notes.txt"; rm -f "$R"/*.bak; commit "edit line 2"
g switch -q main; g merge -q --squash topic >/dev/null 2>&1; g commit -q -m "squash topic"
sed -i.bak 's/line 2 edited on topic/line 2 edited again on main/' "$R/notes.txt"; rm -f "$R"/*.bak; commit "edit line 2 again on main"
g switch -q topic; echo "line 4" >>"$R/notes.txt"; commit "add line 4"
g switch -q main
if g merge -q --no-ff -m "merge topic again" topic >/dev/null 2>&1; then
  out "squash.remerge" "压缩合并 topic 后 main 又改了同一行，topic 继续开发后再合并：成功"
else
  out "squash.remerge" "压缩合并 topic 后 main 又改了同一行，topic 继续开发后再合并：冲突（$(g diff --name-only --diff-filter=U | tr '\n' ' ')），尽管 topic 的那次修改早已在 main 里"
  g merge --abort
fi
out "squash.merged_flag" "git branch --merged main 是否列出 topic = $(g branch --merged main | grep -c topic || true)"
