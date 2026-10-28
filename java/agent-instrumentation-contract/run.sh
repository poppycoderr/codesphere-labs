#!/usr/bin/env bash
# 在容器里编译、打 agent 包并运行；参数是 byte-buddy 的 jar 路径
set -euo pipefail
BB="$1"
rm -rf /tmp/b && mkdir -p /tmp/b/classes
javac -nowarn -cp "$BB" -d /tmp/b/classes $(find src -name '*.java') 2>&1 | grep -v "^Note:" >&2 || true
printf 'Premain-Class: agent.Agent\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n' > /tmp/b/manifest.txt
jar cfm /tmp/b/agent.jar /tmp/b/manifest.txt -C /tmp/b/classes agent
java -javaagent:/tmp/b/agent.jar -cp "/tmp/b/classes:$BB" lab.Main
