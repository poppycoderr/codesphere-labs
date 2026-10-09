#!/bin/sh
# 在容器里运行：生成一次性的三级证书（根、中间、叶）与几个用于对照的叶证书，全部放在 /tmp/pki，容器退出即消失
# 口令是演示值，密钥只存在于这次容器运行中，不进入证据
set -eu
D=/tmp/pki; mkdir -p "$D"; cd "$D"
P=example_password
q() { "$@" >/dev/null 2>&1; }
q openssl req -x509 -newkey rsa:2048 -nodes -keyout root.key -out root.crt -days 3650 -subj "/CN=Lab Root CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
q openssl req -x509 -newkey rsa:2048 -nodes -keyout other-root.key -out other-root.crt -days 3650 -subj "/CN=Unrelated Root CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
q openssl req -newkey rsa:2048 -nodes -keyout inter.key -out inter.csr -subj "/CN=Lab Intermediate CA"
printf 'basicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n' >inter.ext
q openssl x509 -req -in inter.csr -CA root.crt -CAkey root.key -CAcreateserial -out inter.crt -days 1825 -extfile inter.ext
openssl x509 -in inter.crt -outform DER -out inter.der
# leaf <名字> <SAN> <额外扩展> <有效期参数...>
leaf() {
  name="$1"; san="$2"; extra="$3"; shift 3
  q openssl req -newkey rsa:2048 -nodes -keyout "$name.key" -out "$name.csr" -subj "/CN=$name"
  printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=%s\n%s\n' "$san" "$extra" >"$name.ext"
  q openssl x509 -req -in "$name.csr" -CA inter.crt -CAkey inter.key -CAcreateserial -out "$name.crt" -extfile "$name.ext" "$@"
}
leaf good "DNS:localhost" "" -days 90
leaf aia "DNS:localhost" "authorityInfoAccess=caIssuers;URI:http://localhost:18080/inter.der" -days 90
leaf wrong-host "DNS:other.example" "" -days 90
leaf expired "DNS:localhost" "" -not_before 20240101000000Z -not_after 20240401000000Z
# store <名字> <叶> [链上的其他证书]：服务端密钥库
store() {
  name="$1"; l="$2"; shift 2
  if [ $# -gt 0 ]; then cat "$@" >chain.tmp; q openssl pkcs12 -export -inkey "$l.key" -in "$l.crt" -certfile chain.tmp -name server -out "$name.p12" -passout "pass:$P"
  else q openssl pkcs12 -export -inkey "$l.key" -in "$l.crt" -name server -out "$name.p12" -passout "pass:$P"; fi
}
store server-full good inter.crt
store server-leaf-only good
store server-aia-leaf-only aia
store server-wrong-host wrong-host inter.crt
store server-expired expired inter.crt
# trust <名字> <证书...>：客户端信任库
trust() {
  name="$1"; shift; i=0
  for c in "$@"; do i=$((i+1)); q keytool -importcert -noprompt -alias "ca$i" -file "$c" -keystore "$name.p12" -storetype PKCS12 -storepass "$P"; done
}
trust trust-root root.crt
trust trust-root-and-inter root.crt inter.crt
trust trust-other other-root.crt
