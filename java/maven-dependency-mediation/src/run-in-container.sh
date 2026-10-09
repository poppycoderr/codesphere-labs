#!/bin/sh
# 在 Maven 容器里运行：安装实验用的库，再逐个构建应用，记录解析到的 util 版本与运行结果
set -eu
WS=/ws; OUT=/out
M="mvn -B -q -Dstyle.color=never"
for l in util-1.0 util-2.0 lib-a lib-b mid bom-x bom-y; do (cd "$WS/libs/$l" && $M install >/tmp/install.log 2>&1) || { cat /tmp/install.log; exit 1; }; done
: >"$OUT/output.tsv"
for app in nearest order-a-first order-b-first managed direct bom-x-then-y bom-y-then-x own-before-bom explicit-over-managed managed-only; do
  cd "$WS/apps/$app"
  if $M package dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt >/tmp/build.log 2>&1; then build="BUILD SUCCESS"; else build="BUILD FAILURE"; fi
  util=$(tr ':' '\n' </tmp/cp.txt | sed -n 's#.*/util-\([0-9.]*\)\.jar#\1#p'); [ -n "$util" ] || util="不在类路径上"
  run=$(java -cp "target/classes:$(cat /tmp/cp.txt)" labs.app.Main 2>&1 | tail -1)
  printf '%s\t%s\t类路径上的 util 版本 %s\t%s\n' "$app" "$build" "$util" "${run:-（无输出）}" >>"$OUT/output.tsv"
done
cd "$WS/apps/nearest"
mvn -B -Dstyle.color=never dependency:tree -Dverbose 2>/dev/null | sed 's/^\[INFO\] //' | sed -n '/^labs.mediation:app-nearest:jar/,/^---/p' | grep -v '^---' >"$OUT/tree-nearest.txt"
for rule in dependencyConvergence requireUpperBoundDeps; do
  cd "$WS/apps/enforce-$rule"
  if mvn -B -Dstyle.color=never package >/tmp/enf.log 2>&1; then r="BUILD SUCCESS"; else r="BUILD FAILURE"; fi
  { echo "$r"; grep -E "Rule [0-9]+:|convergence error|upper bound dependencies error" /tmp/enf.log | sed 's/^\[ERROR\] //'; } >"$OUT/enforce-$rule.txt"
done
mvn -v | head -1 >"$OUT/maven-version.txt"; java -version 2>&1 | head -1 >>"$OUT/maven-version.txt"
