#!/usr/bin/env bash
# 同一个人的几种角色：大实体加布尔标志、一个类实现多个角色接口、参与方加角色对象的撤销、视图、历史与规则
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器；约 10 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT" "$LABS_CACHE/m2repo"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -w /w "$J25" java -Duser.timezone=UTC src/RoleLab.java 2>/dev/null >"$f"
write_environment "$OUT/environment.txt" "jdk25_image: $J25"
cat "$f" >&2
# 输入固定，并发交错（如有）由闩锁固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
big.revoke	大实体撤销讲师：instructor=false，但 bio=十年后端、hourlyFee=500 仍在；按「有课时费」统计讲师会把她算进去
big.nullable	只是学员的 bob：讲师字段有 2 个为 null；「讲师必须有课时费」只能写成 if (instructor && hourlyFee == null)，在每个修改入口重复
big.history	撤销后再问「alice 9 月份是不是讲师」：只有当前的布尔值 false，历史没有了
iface.cast	把 carol 作为 Learner 传给只该用学员能力的代码：它 instanceof 转回 Person 并调用 grantAdmin()，admin=true；接口是视图，不是权限边界
iface.same_object	Learner 与 Instructor 是同一个对象：true；两个角色共享一份状态和一个生命周期，撤销讲师角色只能靠再加一个标志
role.history	alice 在 9 月 15 日是讲师：true；11 月 15 日：false；讲师角色共 2 段，课时费分别是 500、600
role.invariant	讲师的规则放在讲师角色自己的构造器里，创建课时费为 0 的讲师角色：被拒绝（讲师课时费必须为正：0）；学员角色不需要这条检查
role.count	字段分布：大实体 1 个类 7 个字段；角色对象 1 个参与方（2 个字段）+ 2 种角色（各自 4、4 个字段）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
