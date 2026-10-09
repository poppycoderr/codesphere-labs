#!/usr/bin/env bash
# Maven 依赖仲裁：同一个库的两个版本经不同路径进入依赖树时哪个进类路径；声明顺序、依赖管理、BOM 导入顺序、显式版本各自的效果；Enforcer 的两条规则
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 3.10.0 + temurin 25 容器与宿主机 python3；首次运行要下载插件（缓存在仓库的 .cache/m2repo），约 2 分钟
# 实验用的库（groupId 为 labs.mediation）会安装到这个缓存仓库里，每次运行覆盖
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker python3
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
MVN="maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525"
rm -rf build/ws; python3 src/gen.py build/ws
mkdir -p "$LABS_CACHE/m2repo"
docker run --rm -v "$PWD/build/ws:/ws" -v "$PWD/$OUT:/out" -v "$PWD/src/run-in-container.sh:/run.sh:ro" -v "$LABS_CACHE/m2repo:/root/.m2" "$MVN" sh /run.sh >&2
rm -rf build/ws
write_environment "$OUT/environment.txt" "maven_image: $MVN" "container_maven: $(sed -n 1p "$OUT/maven-version.txt")" "container_java: $(sed -n 2p "$OUT/maven-version.txt")" \
  "maven-dependency-plugin: 3.11.0" "maven-enforcer-plugin: 3.6.3" "maven-compiler-plugin: 3.16.0"
rm "$OUT/maven-version.txt"
cat "$OUT/output.tsv" "$OUT/tree-nearest.txt" "$OUT"/enforce-*.txt >&2
# 解析结果与运行结果是确定的：与预期逐行比较
diff - "$OUT/output.tsv" <<'EXPECTED' || fail "输出与预期不一致"
nearest	BUILD SUCCESS	类路径上的 util 版本 1.0	LibA.shout -> HI；LibB.headline -> NoSuchMethodError: 'java.lang.String labs.util.Text.title(java.lang.String)'
order-a-first	BUILD SUCCESS	类路径上的 util 版本 1.0	LibA.shout -> HI；LibB.headline -> NoSuchMethodError: 'java.lang.String labs.util.Text.title(java.lang.String)'
order-b-first	BUILD SUCCESS	类路径上的 util 版本 2.0	LibA.shout -> HI；LibB.headline -> Hello
managed	BUILD SUCCESS	类路径上的 util 版本 2.0	LibA.shout -> HI；LibB.headline -> Hello
direct	BUILD SUCCESS	类路径上的 util 版本 2.0	LibA.shout -> HI；LibB.headline -> Hello
bom-x-then-y	BUILD SUCCESS	类路径上的 util 版本 1.0	LibA.shout -> HI；LibB.headline -> NoSuchMethodError: 'java.lang.String labs.util.Text.title(java.lang.String)'
bom-y-then-x	BUILD SUCCESS	类路径上的 util 版本 2.0	LibA.shout -> HI；LibB.headline -> Hello
own-before-bom	BUILD SUCCESS	类路径上的 util 版本 2.0	LibA.shout -> HI；LibB.headline -> Hello
explicit-over-managed	BUILD SUCCESS	类路径上的 util 版本 1.0	LibA.shout -> HI；LibB.headline -> NoSuchMethodError: 'java.lang.String labs.util.Text.title(java.lang.String)'
managed-only	BUILD SUCCESS	类路径上的 util 版本 不在类路径上	（无输出）
EXPECTED
expect_regex "$OUT/tree-nearest.txt" "labs.mediation:util:jar:2.0:compile - omitted for conflict with 1.0" "依赖树（verbose）标出 2.0 因冲突被略去"
expect_line "$OUT/enforce-dependencyConvergence.txt" "BUILD FAILURE" "dependencyConvergence：构建失败"
expect_regex "$OUT/enforce-dependencyConvergence.txt" "Dependency convergence error for labs.mediation:util:jar:1.0" "dependencyConvergence 指出 util 的版本不一致"
expect_line "$OUT/enforce-requireUpperBoundDeps.txt" "BUILD FAILURE" "requireUpperBoundDeps：构建失败"
expect_regex "$OUT/enforce-requireUpperBoundDeps.txt" "Require upper bound dependencies error for labs.mediation:util:1.0" "requireUpperBoundDeps 指出解析到的 util 低于被要求的版本"
log "全部通过，输出在 $OUT"
