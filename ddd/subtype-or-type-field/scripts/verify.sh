#!/usr/bin/env bash
# 工时登记目标的建模方式：类型字段与 default 分支、sealed 接口的穷尽检查、违反可替换性的子类、默认值与覆盖值、子类型分表时的身份与引用（PostgreSQL 18）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：一个 PostgreSQL 容器（不暴露端口）和 JDK 25 客户端容器；约 30 秒（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
PROJECT=csl-subtype
C=(docker compose -p "$PROJECT" -f compose.yaml)
"${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${C[@]}" up -d --wait >/dev/null 2>&1
JAR="/cache/m2/$(basename "$(maven_jar org.postgresql:postgresql:42.7.14)")"
f="$OUT/output.tsv"
docker run --rm --network "${PROJECT}_default" -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -cp "$JAR" src/SubtypeLab.java 2>/dev/null >"$f"
{ "${C[@]}" config --images | sed 's/^/image: /'; echo "jdk_image: $J25"; } >"$OUT/container.txt"
write_environment "$OUT/environment.txt" "postgresql: 18（compose.yaml 固定 digest）" "jdbc: postgresql 42.7.14"
cat "$f" >&2
# 输入固定，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1 postgres=18.6
typefield.new_value	新增类型 LEAVE（休假）后没有改任何分支：时薪 0，可计费 true：一处按内部项目处理，另一处按客户项目处理
sealed.before	编译通过
sealed.after	加入 LeaveActivity、不改 Billing：编译失败，第 6 行：the switch expression does not cover all possible input values
lsp.charge_all	对所有登记目标统一 charge(2)：第 2 个目标抛出 UnsupportedOperationException（休假不能计费），第 1 个已经计费 2 小时
default.changed	租户把每日上限从 8 改为 7.5：创建时复制的项目 A 仍是 8；动态继承的项目 A 变成 7.5；覆盖为 10 的项目 B 仍是 10
override.disabled	租户随后关闭「允许项目覆盖」：项目 B 存着的覆盖值仍是 10，生效值变成 7.5；B 之前按 10 小时登记的工时是否仍然有效，要看规则是否带版本或生效日期
per_table.ids	每个具体类型一张表、各自自增：client_project (1, 客户项目 A)，leave_activity (1, 年假)：两个目标的 id 都是 1
per_table.reference	工时表用 (target_type, target_id) 引用，无法声明外键：引用不存在的 LEAVE 99 成功；删除客户项目 1 也成功；现在有 2 条工时指向不存在的目标
super_table.ids	共同父表 work_target 分配身份：(1, CLIENT) (2, LEAVE)，两个目标的 id 不会相同
super_table.dangling	工时引用不存在的目标 99：被拒绝（insert or update on table "t2_entry" violates foreign key constraint "t2_entry_target_id_fkey"）
super_table.delete	删除仍被工时引用的目标 1：被拒绝（update or delete on table "work_target" violates foreign key constraint "t2_entry_target_id_fkey" on table "t2_entry"）
super_table.kind_mismatch	把 LEAVE 目标 2 同时登记成客户项目：被拒绝（insert or update on table "t2_client" violates foreign key constraint "t2_client_id_kind_fkey"）
super_table.missing_child	只插入父表一行 (3, CLIENT)、不插子表：成功；父表里没有子类行的目标 1 个：「恰好有一个子类行」外键表达不了
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT（容器仍在运行，make clean 删除）"
