#!/usr/bin/env bash
# 证书链：服务端只发叶证书、信任了另一个根、域名不符、证书过期时 JDK HttpClient 的握手结果；AIA 下载中间证书的两个开关
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器（自带 openssl 与 keytool）；约 30 秒
# 证书与密钥在容器里现场生成、随容器销毁，口令是演示值，不进入证据
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
AIA="-Dcom.sun.security.enableAIAcaIssuers=true"
docker run --rm -v "$PWD:/w:ro" -v "$PWD/$OUT:/out" -w /w "$J25" sh -c "
  sh scripts/pki.sh
  java src/ChainLab.java /tmp/pki 2>/dev/null >/out/default.tsv
  java $AIA src/ChainLab.java /tmp/pki 2>/dev/null >/out/aia-enabled.tsv
  java $AIA -Dcom.sun.security.allowedAIALocations=http://localhost:18080/ src/ChainLab.java /tmp/pki 2>/dev/null >/out/aia-enabled-and-allowed.tsv
  grep -E '^com.sun.security.allowedAIALocations=' \$JAVA_HOME/conf/security/java.security >/out/java-security-default.txt
  openssl version >/out/openssl-version.txt"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "openssl: $(cat "$OUT/openssl-version.txt")"
rm "$OUT/openssl-version.txt"
cat "$OUT"/default.tsv "$OUT"/aia-enabled.tsv "$OUT"/aia-enabled-and-allowed.tsv >&2
# 握手结果与异常信息在同一个 JDK 构建上是确定的：与预期逐行比较
diff - "$OUT/default.tsv" <<'EXPECTED_DEFAULT' || fail "默认配置的输出与预期不一致"
env	java.version=25.0.4.1 enableAIAcaIssuers=false
full_chain.sent	密钥库里是叶证书加中间证书，客户端只信任根，服务端发来 2 张：CN=good（签发者 CN=Lab Intermediate CA） → CN=Lab Intermediate CA（签发者 CN=Lab Root CA）
full_chain.client	HTTP 200
leaf_only.sent	密钥库里只有叶证书，客户端只信任根，服务端发来 1 张：CN=good（签发者 CN=Lab Intermediate CA）
leaf_only.client	SSLHandshakeException：(certificate_unknown) PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target（根因 SunCertPathBuilderException）
leaf_only.inter_trusted.sent	密钥库里只有叶证书，客户端的信任库里另有中间证书，服务端发来 1 张：CN=good（签发者 CN=Lab Intermediate CA）
leaf_only.inter_trusted.client	HTTP 200
wrong_root.sent	完整的链，客户端信任的是另一个根，服务端发来 2 张：CN=good（签发者 CN=Lab Intermediate CA） → CN=Lab Intermediate CA（签发者 CN=Lab Root CA）
wrong_root.client	SSLHandshakeException：(certificate_unknown) PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target（根因 SunCertPathBuilderException）
wrong_host.sent	完整的链，证书里的域名是 other.example，服务端发来 2 张：CN=wrong-host（签发者 CN=Lab Intermediate CA） → CN=Lab Intermediate CA（签发者 CN=Lab Root CA）
wrong_host.client	SSLHandshakeException：(certificate_unknown) No subject alternative DNS name matching localhost found.（根因 CertificateException）
expired.sent	完整的链，叶证书已过期，服务端发来 2 张：CN=expired（签发者 CN=Lab Intermediate CA） → CN=Lab Intermediate CA（签发者 CN=Lab Root CA）
expired.client	SSLHandshakeException：(certificate_expired) PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed（根因 CertificateExpiredException）
aia_leaf_only.sent	只有叶证书，叶证书带 AIA 扩展指向中间证书的下载地址，客户端只信任根，服务端发来 1 张：CN=aia（签发者 CN=Lab Intermediate CA）
aia_leaf_only.client	SSLHandshakeException：(certificate_unknown) PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target（根因 SunCertPathBuilderException）
aia.downloads	中间证书被下载 0 次
EXPECTED_DEFAULT
diff - "$OUT/aia-enabled.tsv" <<'EXPECTED_AIA' || fail "只打开 enableAIAcaIssuers 的输出与预期不一致"
env	java.version=25.0.4.1 enableAIAcaIssuers=true
aia_leaf_only.sent	只有叶证书，叶证书带 AIA 扩展指向中间证书的下载地址，客户端只信任根，服务端发来 1 张：CN=aia（签发者 CN=Lab Intermediate CA）
aia_leaf_only.client	SSLHandshakeException：(certificate_unknown) PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target（根因 SunCertPathBuilderException）
aia.downloads	中间证书被下载 0 次
EXPECTED_AIA
diff - "$OUT/aia-enabled-and-allowed.tsv" <<'EXPECTED_ALLOWED' || fail "同时设置 allowedAIALocations 的输出与预期不一致"
env	java.version=25.0.4.1 enableAIAcaIssuers=true
aia_leaf_only.sent	只有叶证书，叶证书带 AIA 扩展指向中间证书的下载地址，客户端只信任根，服务端发来 1 张：CN=aia（签发者 CN=Lab Intermediate CA）
aia_leaf_only.client	HTTP 200
aia.downloads	中间证书被下载 1 次
EXPECTED_ALLOWED
expect_line "$OUT/java-security-default.txt" "com.sun.security.allowedAIALocations=" "java.security 里 allowedAIALocations 默认为空（全部拒绝）"
log "全部通过，输出在 $OUT"
