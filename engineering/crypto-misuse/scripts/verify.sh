#!/usr/bin/env bash
# 加密与口令存储的误用：默认的 ECB、重复使用的 GCM 随机数、没有认证的 CBC、没有绑定上下文的密文、快速哈希存口令、BCrypt 的 72 字节上限、可预测的 java.util.Random
# 用法：scripts/verify.sh [输出目录]，默认 build/run；make evidence 时输出到 evidence/
# 资源：固定 digest 的 temurin 25 容器，依赖的 jar 从 Maven Central 下载到缓存目录；约 20 秒。密钥与随机数是固定的演示值，结论行确定；哈希速度另存在 timings.log，不参与比较
set -euo pipefail
cd "$(dirname "$0")/.."
source ../../shared/scripts/lib.sh
require docker
OUT="${1:-build/run}"; mkdir -p "$OUT"; find "$OUT" -mindepth 1 ! -name README.md -delete
J25="eclipse-temurin@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
COORDS="org.springframework.security:spring-security-crypto:7.1.1 org.springframework:spring-core:7.0.9 commons-logging:commons-logging:1.3.5 org.mindrot:jbcrypt:0.4"
JARS=""
for coord in $COORDS; do JARS="$JARS:/cache/m2/$(basename "$(maven_jar "$coord")")"; done
f="$OUT/output.tsv"
docker run --rm -e TZ=UTC -v "$PWD:/w" -v "$LABS_CACHE:/cache" -w /w "$J25" java -Duser.timezone=UTC  -cp "${JARS#:}" src/CryptoLab.java 2>"$OUT/stderr.log" >"$f"
grep '^timing' "$OUT/stderr.log" >"$OUT/timings.log" || true; rm -f "$OUT/stderr.log"
write_environment "$OUT/environment.txt" "jdk25_image: $J25" "libraries: $COORDS"
cat "$f" >&2
# 场景顺序执行，输出是确定的：与预期逐行比较
diff - "$f" <<'EXPECTED' || fail "输出与预期不一致"
env	java.version=25.0.4.1
ecb.default	Cipher.getInstance("AES") 加密三段相同的 16 字节：第 1、2、3 个密文块 7136ed88…、7136ed88…、7136ed88…；三块完全相同 = true
ecb.gcm	同样的明文用 AES/GCM/NoPadding：前两个密文块相同 = false
nonce.reuse	两条消息用了同一个随机数：两段密文异或 == 两段明文异或 = true
nonce.recover	攻击者知道第一条的明文、截获两段密文，不需要密钥算出第二条：pay 900 to carol, memo: bonus
nonce.same_instance	同一个 Cipher 对象不重新 init 就加密第二条：抛出 IllegalStateException（Must use either different key or  iv for GCM encryption）
nonce.new_instance	每次 new 一个 Cipher、传入同样的随机数：45 字节，没有任何报错
cbc.bit_flip	AES/CBC：攻击者不知道密钥，把 IV 的一个字节异或一下，解密得到 amount=900&to=bob&ref=20261010
gcm.tamper	AES/GCM：同样改一个字节后解密：抛出 AEADBadTagException（Tag mismatch）
aad.none	把 alice 那一行的密文和随机数原样复制到 bob 那一行，解密：balance=99000
aad.bound	加密时把 user=alice 作为附加认证数据，在 bob 那一行用 user=bob 解密：抛出 AEADBadTagException（Tag mismatch）
password.sha256_same	SHA-256 不加盐：两个用户都用 password123，存下来的值相同 = true
password.sha256_speed	单线程每秒能算的 SHA-256 超过 50 万次 = true
password.pbkdf2	PBKDF2-HMAC-SHA256、60 万次迭代、每人一个盐：同样的口令存下来的值相同 = false；算一次超过 30 ms = true；猜一次的代价是不加盐 SHA-256 的 1 万倍以上 = true
bcrypt.jbcrypt	jBCrypt 0.4：存入 72 个 a 加 -correct-horse，用 72 个 a 加 -wrong 校验：通过 = true
bcrypt.multibyte	24 个汉字是 72 字节；jBCrypt 存入这 24 个字加「安全」，用这 24 个字加「随便」校验：通过 = true
bcrypt.spring_encode	Spring Security 7.1.1 的 BCryptPasswordEncoder.encode 传入这个 86 字节的口令：抛出 IllegalArgumentException（password cannot be more than 72 bytes）
bcrypt.spring_encode_72	同一个编码器，传入正好 72 字节的口令：$2a$04$…（正常返回）
bcrypt.spring_matches_old	用它校验旧库存下来的哈希，输入原来那个 86 字节的正确口令：matches = true
bcrypt.spring_matches_wrong	输入 72 个 a 加 -wrong：matches = true
bcrypt.spring_low_level	底层的 BCrypt.checkpw，同样两个输入：正确口令 true；错误口令 true
random.predict	看到 java.util.Random 连续输出的两个 int，最多试 65536 次就能算出下一个：预测值与实际值相同 = true
random.secure	SecureRandom 生成的 16 字节令牌长度 = 16（值不可预测，不参与比较）
EXPECTED
log "全部通过：输出与预期逐行一致，输出在 $OUT"
