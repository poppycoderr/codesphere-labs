#!/usr/bin/env bash
# 合并结果没有被检查过：两个分支各自通过、合并后失败；撤销合并提交后再次合并；压缩合并后继续在原分支开发
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：宿主机的 git（2.38 及以上，需要 merge-tree --write-tree）与 python3；在 build/ 下建临时仓库，约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require git python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
gv=$(git --version | awk '{print $3}')
awk -v v="$gv" 'BEGIN{split(v,a,"."); exit !(a[1]>2 || (a[1]==2 && a[2]>=38))}' || fail "需要 git 2.38 及以上，当前 $gv"
f="$OUT/output.tsv"
bash src/scenario.sh "$PWD/build/work" >"$f"
rm -rf build/work
write_environment "$OUT/environment.txt" "git: $gv" "python: $(python3 --version | awk '{print $2}')"
cat "$f" >&2
# 身份与时间固定、输出不含提交哈希，结果是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
skew.rename_branch	分支 rename（把 total 改名为 subtotal 并改掉全部调用方）上的检查：通过
skew.invoice_branch	分支 invoice（新增一个调用 total 的文件）上的检查：通过
skew.merge_tree	main 已合入 rename；预先计算 main 与 invoice 的合并结果：无文本冲突；在这个结果上运行检查：失败（AttributeError: module 'pricing' has no attribute 'total'）
skew.up_to_date_branch	要求分支先合入最新的 main：invoice 分支更新后的检查：失败（AttributeError: module 'pricing' has no attribute 'total'）
skew.merged_main	直接合并 invoice：文本冲突 0 处，合并提交已生成；main 上的检查：失败（AttributeError: module 'pricing' has no attribute 'total'）
revert.after_merge	合并 feature 之后 main 上的 feature.txt：feature part 1|feature part 2|
revert.after_revert	撤销这个合并提交之后 feature.txt 存在 = false
revert.remerge_unchanged	feature 分支没有新提交时再次合并：Already up to date.
revert.remerge_with_fix	feature 追加一个修复提交（新文件 feature_fix.txt）后再次合并：成功；main 上 feature_fix.txt 存在 = true，feature.txt 存在 = false
revert.remerge_same_file	feature 再追加一个修改 feature.txt 的提交后合并：冲突（DU feature.txt：main 认为这个文件已删除，feature 认为它被修改）
revert.revert_the_revert	先撤销那次撤销，再合并 feature：feature.txt：feature part 1|feature part 2|feature part 3|；feature_fix.txt 存在 = true
squash.remerge	压缩合并 topic 后 main 又改了同一行，topic 继续开发后再合并：冲突（notes.txt ），尽管 topic 的那次修改早已在 main 里
squash.merged_flag	git branch --merged main 是否列出 topic = 0
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
