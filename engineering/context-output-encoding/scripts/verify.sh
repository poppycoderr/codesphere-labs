#!/usr/bin/env bash
# 同一份数据在 HTML 正文、属性、脚本与 URL 中的输出边界，CSP nonce 与 Cookie 属性（Thymeleaf 3.1.5、Chromium 153）
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 Maven 镜像与 Playwright 镜像（约 2.4 GB），页面与服务都在容器内；约 2 分钟（不含拉取镜像）
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"
mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
mkdir -p build/site "$LABS_CACHE/m2" "$LABS_CACHE/pip"
MAVEN=maven@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525
PW=mcr.microsoft.com/playwright/python@sha256:72bd171a9ffc2b4b59532aaa6210e21014d07093120dc25528870c0b840da1f0
docker run --rm --cpus=2 --memory=2g -v "$PWD:/w" -v "$LABS_CACHE/m2:/root/.m2" -w /w "$MAVEN" sh -c 'mvn -q package dependency:build-classpath -Dmdep.outputFile=build/classpath && java -cp "target/classes:$(cat build/classpath)" Render' >"$OUT/template.txt" 2>build/maven.log
docker run --rm --cpus=2 --memory=2g -v "$PWD:/w:ro" -v "$LABS_CACHE/pip:/root/.cache/pip" -w /w "$PW" sh -c 'pip install -q --disable-pip-version-check --root-user-action=ignore --break-system-packages -r requirements.txt && python3 src/browser.py' >"$OUT/results.json"
cp build/site/template.html "$OUT/rendered.html"
expect_regex "$OUT/template.txt" '^thymeleaf=3\.1\.5\.RELEASE java=25\.0\.4\.1'
expect_regex "$OUT/results.json" '"chromium": "153\.0\.8010\.12"'
write_environment "$OUT/environment.txt" "maven_image: $MAVEN" "browser_image: $PW"
cat "$OUT/results.json"
