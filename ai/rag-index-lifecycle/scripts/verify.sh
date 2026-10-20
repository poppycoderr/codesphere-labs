#!/usr/bin/env bash
# 知识库的增量更新：内容哈希键留下旧块、删除不传播、换嵌入模型混用、权限过滤、答案缓存的键（Python 3.14）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 5 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm --network=none -v "$PWD/src:/src:ro" "$PY" python /src/lab.py >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY"
cat "$f" >&2
# 嵌入是确定性的假实现，输入固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
edit.naive	policy.md 从 7 天改成 15 天后重新导入，只按内容哈希做键：索引里 3 块；查「退货期限是多少天」得到 policy.md:退货期限为签收后 15 天 / policy.md:退货期限为签收后 7 天
edit.managed	按来源记录块，重建时删掉不再产生的块：索引里 2 块；查「退货期限是多少天」得到 policy.md:退货期限为签收后 15 天 / policy.md:运费由买家承担
delete.naive	old-faq.md 从仓库删除之后，朴素索引只做增量导入：查「退货期限是多少天」仍得到 old-faq.md:旧版：退货期限为签收后 3 天 / policy.md:退货期限为签收后 15 天 / policy.md:退货期限为签收后 7 天
delete.managed	全量同步：删除前 old-faq.md:旧版：退货期限为签收后 3 天 / policy.md:退货期限为签收后 15 天 / policy.md:运费由买家承担；删除后 policy.md:退货期限为签收后 15 天 / policy.md:运费由买家承担
model.mixed_score	同一模型下问题与答案的相似度 0.33；问题换成新模型嵌入、文档还是旧模型的向量 0.11
model.guard	每条向量记录嵌入模型，查询时检查：索引里有 ['emb-v1'] 生成的向量，查询用的是 emb-v2：拒绝混用
model.dim	维度不同的模型直接混用：维度不一致：64 与 48
acl.filter	员工（只属于 all）查「退货专员的待遇」：policy.md:退货期限为签收后 15 天 / policy.md:运费由买家承担；人事（all、hr）查：salary.md:退货专员的薪资档位为 B3
cache.query_only	答案缓存只用问题文本做键，人事先问过一次，员工再问：salary.md:退货专员的薪资档位为 B3
cache.scoped	缓存键加上权限范围：员工再问：policy.md:退货期限为签收后 15 天
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
