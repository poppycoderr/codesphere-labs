import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 用了加密算法、结果仍然不安全的几种写法：默认的 ECB、重复使用的 GCM 随机数、没有认证的 CBC、没有绑定上下文的密文、
 * 快速哈希存口令、BCrypt 的 72 字节上限、用 java.util.Random 生成令牌。密钥与随机数都是固定的演示值，输出确定。
 */
public class CryptoLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static final HexFormat HEX = HexFormat.of();
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
    static byte[] xor(byte[] a, byte[] b) { byte[] r = new byte[a.length]; for (int i = 0; i < a.length; i++) r[i] = (byte) (a[i] ^ b[i]); return r; }
    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "抛出 " + e.getClass().getSimpleName() + "（" + e.getMessage() + "）"; } }

    static final SecretKey KEY = new SecretKeySpec(HEX.parseHex("000102030405060708090a0b0c0d0e0f"), "AES");   // 演示用的固定密钥
    static final byte[] NONCE = HEX.parseHex("0102030405060708090a0b0c");

    static byte[] gcm(int mode, byte[] nonce, byte[] aad, byte[] input) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, KEY, new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(input);
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、Cipher.getInstance("AES") 用的是什么模式
        Cipher plain = Cipher.getInstance("AES");
        plain.init(Cipher.ENCRYPT_MODE, KEY);
        byte[] ecb = plain.doFinal(utf8("card=4111111111;card=4111111111;card=4111111111;"));
        out("ecb.default", "Cipher.getInstance(\"AES\") 加密三段相同的 16 字节：第 1、2、3 个密文块 " + HEX.formatHex(ecb, 0, 4) + "…、" + HEX.formatHex(ecb, 16, 20) + "…、" + HEX.formatHex(ecb, 32, 36)
                + "…；三块完全相同 = " + (Arrays.equals(ecb, 0, 16, ecb, 16, 32) && Arrays.equals(ecb, 0, 16, ecb, 32, 48)));
        byte[] g = gcm(Cipher.ENCRYPT_MODE, NONCE, null, utf8("card=4111111111;card=4111111111;card=4111111111;"));
        out("ecb.gcm", "同样的明文用 AES/GCM/NoPadding：前两个密文块相同 = " + Arrays.equals(g, 0, 16, g, 16, 32));

        // 二、同一个密钥下重复使用 GCM 的随机数
        byte[] p1 = utf8("pay 100 to alice, memo: rent "), p2 = utf8("pay 900 to carol, memo: bonus");
        byte[] c1 = gcm(Cipher.ENCRYPT_MODE, NONCE, null, p1), c2 = gcm(Cipher.ENCRYPT_MODE, NONCE, null, p2);
        byte[] body1 = Arrays.copyOf(c1, p1.length), body2 = Arrays.copyOf(c2, p2.length);
        out("nonce.reuse", "两条消息用了同一个随机数：两段密文异或 == 两段明文异或 = " + Arrays.equals(xor(body1, body2), xor(p1, p2)));
        out("nonce.recover", "攻击者知道第一条的明文、截获两段密文，不需要密钥算出第二条：" + text(xor(xor(body1, body2), p1)));
        Cipher same = Cipher.getInstance("AES/GCM/NoPadding");
        same.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(128, NONCE)); same.doFinal(p1);
        out("nonce.same_instance", "同一个 Cipher 对象不重新 init 就加密第二条：" + attempt(() -> same.doFinal(p2).length));
        out("nonce.new_instance", "每次 new 一个 Cipher、传入同样的随机数：" + attempt(() -> gcm(Cipher.ENCRYPT_MODE, NONCE, null, p2).length + " 字节，没有任何报错"));

        // 三、只加密、不认证：改密文
        byte[] iv = HEX.parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
        Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cbc.init(Cipher.ENCRYPT_MODE, KEY, new IvParameterSpec(iv));
        byte[] order = cbc.doFinal(utf8("amount=100&to=bob&ref=20261010"));
        byte[] forgedIv = iv.clone(); forgedIv[7] ^= (byte) ('1' ^ '9');                 // 第 8 个字节是金额的首位
        Cipher dec = Cipher.getInstance("AES/CBC/PKCS5Padding");
        dec.init(Cipher.DECRYPT_MODE, KEY, new IvParameterSpec(forgedIv));
        out("cbc.bit_flip", "AES/CBC：攻击者不知道密钥，把 IV 的一个字节异或一下，解密得到 " + text(dec.doFinal(order)));
        byte[] sealed = gcm(Cipher.ENCRYPT_MODE, NONCE, null, utf8("amount=100&to=bob&ref=20261010"));
        byte[] tampered = sealed.clone(); tampered[7] ^= (byte) ('1' ^ '9');
        out("gcm.tamper", "AES/GCM：同样改一个字节后解密：" + attempt(() -> text(gcm(Cipher.DECRYPT_MODE, NONCE, null, tampered))));

        // 四、密文没有绑定它属于谁
        byte[] nonceA = HEX.parseHex("0a0000000000000000000001");
        byte[] aliceBalance = gcm(Cipher.ENCRYPT_MODE, nonceA, null, utf8("balance=99000"));
        out("aad.none", "把 alice 那一行的密文和随机数原样复制到 bob 那一行，解密：" + attempt(() -> text(gcm(Cipher.DECRYPT_MODE, nonceA, null, aliceBalance))));
        byte[] bound = gcm(Cipher.ENCRYPT_MODE, nonceA, utf8("user=alice"), utf8("balance=99000"));
        out("aad.bound", "加密时把 user=alice 作为附加认证数据，在 bob 那一行用 user=bob 解密：" + attempt(() -> text(gcm(Cipher.DECRYPT_MODE, nonceA, utf8("user=bob"), bound))));

        // 五、口令怎么存
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        out("password.sha256_same", "SHA-256 不加盐：两个用户都用 password123，存下来的值相同 = " + Arrays.equals(sha.digest(utf8("password123")), sha.digest(utf8("password123"))));
        long t = System.nanoTime(); int n = 0;
        while (System.nanoTime() - t < 300_000_000L) { sha.digest(utf8("guess" + n)); n++; }
        double shaPerSecond = n / 0.3;
        System.err.println("timing\tsha256_per_second\t" + Math.round(shaPerSecond));
        out("password.sha256_speed", "单线程每秒能算的 SHA-256 超过 50 万次 = " + (shaPerSecond > 500_000));
        byte[] saltA = HEX.parseHex("00112233445566778899aabbccddeeff"), saltB = HEX.parseHex("ffeeddccbbaa99887766554433221100");
        SecretKeyFactory pbkdf2 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        t = System.nanoTime();
        byte[] hA = pbkdf2.generateSecret(new PBEKeySpec("password123".toCharArray(), saltA, 600_000, 256)).getEncoded();
        long pbkdfMs = (System.nanoTime() - t) / 1_000_000;
        byte[] hB = pbkdf2.generateSecret(new PBEKeySpec("password123".toCharArray(), saltB, 600_000, 256)).getEncoded();
        System.err.println("timing\tpbkdf2_600000_ms\t" + pbkdfMs);
        out("password.pbkdf2", "PBKDF2-HMAC-SHA256、60 万次迭代、每人一个盐：同样的口令存下来的值相同 = " + Arrays.equals(hA, hB) + "；算一次超过 30 ms = " + (pbkdfMs > 30)
                + "；猜一次的代价是不加盐 SHA-256 的 1 万倍以上 = " + (pbkdfMs / 1000.0 * shaPerSecond > 10_000));

        // 六、BCrypt 只看前 72 个字节
        String base = "a".repeat(72), stored = base + "-correct-horse", wrong = base + "-wrong";
        String oldHash = org.mindrot.jbcrypt.BCrypt.hashpw(stored, org.mindrot.jbcrypt.BCrypt.gensalt(4));
        out("bcrypt.jbcrypt", "jBCrypt 0.4：存入 72 个 a 加 -correct-horse，用 72 个 a 加 -wrong 校验：通过 = " + org.mindrot.jbcrypt.BCrypt.checkpw(wrong, oldHash));
        String chinese = "密码".repeat(12);                                               // 24 个汉字，UTF-8 下正好 72 字节
        out("bcrypt.multibyte", "24 个汉字是 " + utf8(chinese).length + " 字节；jBCrypt 存入这 24 个字加「安全」，用这 24 个字加「随便」校验：通过 = "
                + org.mindrot.jbcrypt.BCrypt.checkpw(chinese + "随便", org.mindrot.jbcrypt.BCrypt.hashpw(chinese + "安全", org.mindrot.jbcrypt.BCrypt.gensalt(4))));
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        out("bcrypt.spring_encode", "Spring Security 7.1.1 的 BCryptPasswordEncoder.encode 传入这个 86 字节的口令：" + attempt(() -> encoder.encode(stored).substring(0, 7) + "…（正常返回）"));
        out("bcrypt.spring_encode_72", "同一个编码器，传入正好 72 字节的口令：" + attempt(() -> encoder.encode(base).substring(0, 7) + "…（正常返回）"));
        out("bcrypt.spring_matches_old", "用它校验旧库存下来的哈希，输入原来那个 86 字节的正确口令：" + attempt(() -> "matches = " + encoder.matches(stored, oldHash)));
        out("bcrypt.spring_matches_wrong", "输入 72 个 a 加 -wrong：" + attempt(() -> "matches = " + encoder.matches(wrong, oldHash)));
        out("bcrypt.spring_low_level", "底层的 BCrypt.checkpw，同样两个输入：" + attempt(() -> "正确口令 " + BCrypt.checkpw(stored, oldHash)) + "；" + attempt(() -> "错误口令 " + BCrypt.checkpw(wrong, oldHash)));

        // 七、用 java.util.Random 生成令牌
        Random random = new Random(20261010L);
        int o1 = random.nextInt(), o2 = random.nextInt(), o3 = random.nextInt();
        Integer predicted = null;
        for (long low = 0; low < 65536 && predicted == null; low++) {
            long state = ((long) o1 << 16 | low) & ((1L << 48) - 1);
            long next = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            if ((int) (next >>> 16) == o2) predicted = (int) (((next * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1)) >>> 16);
        }
        out("random.predict", "看到 java.util.Random 连续输出的两个 int，最多试 65536 次就能算出下一个：预测值与实际值相同 = " + (predicted != null && predicted == o3));
        byte[] token = new byte[16]; new SecureRandom().nextBytes(token);
        out("random.secure", "SecureRandom 生成的 16 字节令牌长度 = " + token.length + "（值不可预测，不参与比较）");
    }
}
