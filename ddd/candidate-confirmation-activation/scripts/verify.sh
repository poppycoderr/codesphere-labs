#!/usr/bin/env bash
# 候选、确认与生效：抽取结果的确认与模型版本的部署指针共用来源哈希、版本条件、幂等回执与尝试号（SQLite）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 python:3.14.8-slim 容器，只用标准库；约 10 秒
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/pip"; find "$OUT" -mindepth 1 ! -name README.md -delete
PY="python:3.14.8-slim@sha256:0741d101873c12ab927e6f8653feb8862b9bd58771177acb1b885b95141f91b4"
f="$OUT/output.tsv"
docker run --rm -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PY" sh -c 'pip install -q --root-user-action=ignore --disable-pip-version-check -r requirements.txt >/dev/null 2>&1 && python -B src/lab.py' >"$f"
write_environment "$OUT/environment.txt" "python_image: $PY" 
cat "$f" >&2
# 输入固定、顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	sqlite=3.46.1
extract.pending	抽取出候选 c1（金额 100）之后，正式记录：金额 0、版本 0
extract.confirm	请求 r1 按版本 0 确认 c1：已确认；正式记录：金额 100、版本 1
extract.duplicate	r1 原样重发：重复请求，返回原结果；正式记录：金额 100、版本 1
extract.reused_key	r1 换了参数（版本 1）再发：拒绝：同一个请求 ID 换了参数
extract.stale_version	另一位审核人基于版本 0 确认候选 c2（金额 999）：拒绝：业务版本已变化
extract.stale_source	文档更新为第 2 版后，按最新版本 1 确认 c2：拒绝：候选引用的来源已经变了；正式记录：金额 100、版本 1
extract.reextract	对第 2 版重新抽取出 c3（金额 200），按版本 1 确认：已确认；正式记录：金额 200、版本 2
extract.callback	抽取任务已是第 2 次尝试：第 1 次尝试的迟到回调更新 0 行，第 2 次的回调更新 1 行，同一回调重复到达更新 0 行
release.candidate	训练出 m2（状态 candidate），切换到 m2：拒绝：m2 未通过验证；指向 m1、版本 0
release.activate	m2 通过验证后按版本 0 切换：已切换；指向 m2、版本 1
release.stale	另一个发布请求仍基于版本 0 要切回 m1：拒绝：部署版本已变化；指向 m2、版本 1
release.rollback	按版本 1 回退到 m1：已切换；指向 m1、版本 2（回退也是一次新版本，不是把版本减回去）
release.reopen	关闭连接后重新打开：指向 m1、版本 2；金额 200、版本 2
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
