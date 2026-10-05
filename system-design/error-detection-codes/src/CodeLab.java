import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 检错与纠错的边界：奇偶位、求和校验、CRC、Hamming(7,4) 与扩展 Hamming(8,4)、CRC 与 HMAC 的区别。穷举，输出确定。 */
public class CodeLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static int parity(int v) { return Integer.bitCount(v) & 1; }

    // 16 位反码求和（RFC 1071 的算法），按大端两字节一组
    static int inet(byte[] b) {
        long s = 0;
        for (int i = 0; i < b.length; i += 2) s += ((b[i] & 0xff) << 8) | (i + 1 < b.length ? b[i + 1] & 0xff : 0);
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    static long crc32(byte[] b) { CRC32 c = new CRC32(); c.update(b); return c.getValue(); }

    // CRC-8，多项式 x^8+x^2+x+1（0x07），初值 0，不反转
    static int crc8(byte[] b) {
        int c = 0;
        for (byte x : b) { c ^= x & 0xff; for (int i = 0; i < 8; i++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xff : (c << 1) & 0xff; }
        return c;
    }

    // Hamming(7,4)：位置 1..7，校验位在 1、2、4，数据位在 3、5、6、7；返回的整数第 i 位（从 1 起）对应位置 i
    static int h74(int d) {
        int[] p = new int[8];
        p[3] = d & 1; p[5] = (d >> 1) & 1; p[6] = (d >> 2) & 1; p[7] = (d >> 3) & 1;
        p[1] = p[3] ^ p[5] ^ p[7]; p[2] = p[3] ^ p[6] ^ p[7]; p[4] = p[5] ^ p[6] ^ p[7];
        int w = 0; for (int i = 1; i <= 7; i++) w |= p[i] << i;
        return w;
    }
    static int syndrome(int w) { int s = 0; for (int i = 1; i <= 7; i++) if (((w >> i) & 1) != 0) s ^= i; return s; }
    static int data(int w) { return ((w >> 3) & 1) | (((w >> 5) & 1) << 1) | (((w >> 6) & 1) << 2) | (((w >> 7) & 1) << 3); }
    static int decode74(int w) { int s = syndrome(w); if (s != 0) w ^= 1 << s; return data(w); }

    // 扩展 Hamming(8,4)：第 0 位放全体奇偶位。返回数据，-1 表示发现不可纠正的错误
    static int h84(int d) { int w = h74(d); return w | parity(w); }
    static int decode84(int w) {
        int s = syndrome(w & 0xfe), overall = parity(w);
        if (s == 0 && overall == 0) return data(w);
        if (overall == 1) { if (s != 0) w ^= 1 << s; return data(w); }   // 奇数个错误：按单比特纠正
        return -1;                                                        // 校验子非零而总奇偶正确：两位错
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、奇偶位：8 位数据 + 1 位偶校验
        int det1 = 0, det2 = 0, det3 = 0, n1 = 0, n2 = 0, n3 = 0;
        int word = 0b10110010; int cw = word | (parity(word) << 8);
        for (int e = 1; e < 512; e++) {
            int bits = Integer.bitCount(e); if (bits > 3) continue;
            boolean detected = parity(cw ^ e) != 0;
            if (bits == 1) { n1++; if (detected) det1++; } else if (bits == 2) { n2++; if (detected) det2++; } else { n3++; if (detected) det3++; }
        }
        out("parity", "9 位码字：1 位翻转发现 " + det1 + "/" + n1 + "，2 位翻转发现 " + det2 + "/" + n2 + "，3 位翻转发现 " + det3 + "/" + n3);

        // 二、求和校验：换序与一加一减
        byte[] m = "pay=100;to=A;seq=0009;pad=..".getBytes(StandardCharsets.US_ASCII);
        byte[] swapped = m.clone(); for (int i = 0; i < 2; i++) { byte t = swapped[i]; swapped[i] = swapped[i + 8]; swapped[i + 8] = t; }
        byte[] comp = m.clone(); comp[4] += 8; comp[20] -= 8;   // 金额 100 变 900，序号 0009 变 0001
        out("sum.swap", "两个 16 位字交换位置（" + new String(swapped, StandardCharsets.US_ASCII) + "）：16 位反码和不变 = " + (inet(m) == inet(swapped))
                + "；CRC-32 不变 = " + (crc32(m) == crc32(swapped)));
        out("sum.compensate", "一处加 8、同一列另一处减 8（" + new String(comp, StandardCharsets.US_ASCII) + "）：16 位反码和不变 = " + (inet(m) == inet(comp))
                + "；CRC-32 不变 = " + (crc32(m) == crc32(comp)));

        // 三、CRC-8：3 字节消息 + 1 字节校验，共 32 位，穷举错误图样
        byte[] msg = {0x12, 0x34, 0x56};
        byte[] code = {0x12, 0x34, 0x56, (byte) crc8(msg)};
        long[] total = new long[5], missed = new long[5];
        for (int a = 0; a < 32; a++) {
            count(code, 1L << a, 1, total, missed);
            for (int b = a + 1; b < 32; b++) {
                count(code, (1L << a) | (1L << b), 2, total, missed);
                for (int c = b + 1; c < 32; c++) {
                    count(code, (1L << a) | (1L << b) | (1L << c), 3, total, missed);
                    for (int d = c + 1; d < 32; d++) count(code, (1L << a) | (1L << b) | (1L << c) | (1L << d), 4, total, missed);
                }
            }
        }
        out("crc8.by_weight", "32 位码字，漏检数/图样数：1 位 " + missed[1] + "/" + total[1] + "，2 位 " + missed[2] + "/" + total[2]
                + "，3 位 " + missed[3] + "/" + total[3] + "，4 位 " + missed[4] + "/" + total[4]);
        long burstTotal = 0, burstMissed = 0, burst9Total = 0, burst9Missed = 0;
        for (int len = 2; len <= 9; len++)
            for (int start = 0; start + len <= 32; start++)
                for (long mid = 0; mid < (1L << (len - 2)); mid++) {
                    long e = ((1L << (len - 1)) | (mid << 1) | 1L) << start;
                    boolean miss = !detects(code, e);
                    if (len <= 8) { burstTotal++; if (miss) burstMissed++; } else { burst9Total++; if (miss) burst9Missed++; }
                }
        out("crc8.burst", "长度 2—8 的突发错误漏检 " + burstMissed + "/" + burstTotal + "；长度 9 的突发错误漏检 " + burst9Missed + "/" + burst9Total);
        long all = 0, allMissed = 0;
        for (long e = 1; e < (1L << 24); e++) { all++; if (!detects(code, e << 4)) allMissed++; }   // 覆盖 24 位窗口内的全部图样
        out("crc8.random", "一个 24 位窗口内的全部 " + all + " 种错误图样，漏检 " + allMissed + "，占 " + String.format("%.4f%%", 100.0 * allMissed / all) + "（1/256 = 0.3906%）");

        // 四、CRC 不是防篡改：等长消息上 crc(a^b^c) = crc(a)^crc(b)^crc(c)
        byte[] a = "amount=0100;to=alice".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "amount=0100;to=bobby".getBytes(StandardCharsets.US_ASCII);
        byte[] c = "amount=9900;to=alice".getBytes(StandardCharsets.US_ASCII);
        byte[] x = new byte[a.length]; for (int i = 0; i < a.length; i++) x[i] = (byte) (a[i] ^ b[i] ^ c[i]);
        out("crc32.linear", "三条等长消息 a、b、c：crc(a^b^c) 与 crc(a)^crc(b)^crc(c) 相等 = " + (crc32(x) == (crc32(a) ^ crc32(b) ^ crc32(c)))
                + "；a^b^c = " + new String(x, StandardCharsets.US_ASCII));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("example_key".getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        byte[] ha = mac.doFinal(a), hb = mac.doFinal(b), hc = mac.doFinal(c), hx = mac.doFinal(x);
        boolean same = true; for (int i = 0; i < ha.length; i++) if ((byte) (ha[i] ^ hb[i] ^ hc[i]) != hx[i]) same = false;
        out("hmac.linear", "同样的关系对 HMAC-SHA256 成立 = " + same);

        // 五、Hamming(7,4)：穷举 16 个数据字
        int ok1 = 0, t1 = 0, wrong2 = 0, t2 = 0, clean2 = 0;
        for (int d = 0; d < 16; d++) {
            int w = h74(d);
            for (int i = 1; i <= 7; i++) { t1++; if (decode74(w ^ (1 << i)) == d) ok1++; }
            for (int i = 1; i <= 7; i++) for (int j = i + 1; j <= 7; j++) {
                t2++; int r = w ^ (1 << i) ^ (1 << j);
                if (syndrome(r) == 0) clean2++;
                if (decode74(r) != d) wrong2++;
            }
        }
        int minDist = 99; for (int i = 0; i < 16; i++) for (int j = i + 1; j < 16; j++) minDist = Math.min(minDist, Integer.bitCount(h74(i) ^ h74(j)));
        out("h74.single", "1 位错纠正为原数据 " + ok1 + "/" + t1 + "；码字之间的最小距离 " + minDist);
        out("h74.double", "2 位错：校验子为 0（看起来没错）" + clean2 + "/" + t2 + "，被「纠正」成另一个数据 " + wrong2 + "/" + t2);

        // 六、扩展 Hamming(8,4)
        int e1 = 0, e1t = 0, e2det = 0, e2t = 0, e3wrong = 0, e3det = 0, e3t = 0, e4silent = 0, e4det = 0, e4t = 0;
        for (int d = 0; d < 16; d++) {
            int w = h84(d);
            for (int e = 1; e < 256; e++) {
                int bits = Integer.bitCount(e); int r = decode84(w ^ e);
                if (bits == 1) { e1t++; if (r == d) e1++; }
                else if (bits == 2) { e2t++; if (r == -1) e2det++; }
                else if (bits == 3) { e3t++; if (r == -1) e3det++; else if (r != d) e3wrong++; }
                else if (bits == 4) { e4t++; if (r == -1) e4det++; else if (r != d) e4silent++; }
            }
        }
        int minDist8 = 99; for (int i = 0; i < 16; i++) for (int j = i + 1; j < 16; j++) minDist8 = Math.min(minDist8, Integer.bitCount(h84(i) ^ h84(j)));
        out("h84.single", "1 位错纠正为原数据 " + e1 + "/" + e1t + "；最小距离 " + minDist8);
        out("h84.double", "2 位错报告为不可纠正 " + e2det + "/" + e2t);
        out("h84.triple", "3 位错：报告为不可纠正 " + e3det + "/" + e3t + "，被当作 1 位错「纠正」成另一个数据 " + e3wrong + "/" + e3t);
        out("h84.quad", "4 位错：报告为不可纠正 " + e4det + "/" + e4t + "，无任何提示地得到另一个数据 " + e4silent + "/" + e4t);
        out("overhead", "冗余位占比：奇偶 1/9，Hamming(7,4) 3/7，扩展 Hamming(8,4) 4/8，(72,64) SECDED 8/72");
    }

    static boolean detects(byte[] code, long e) {
        byte[] r = code.clone();
        for (int i = 0; i < 4; i++) r[i] ^= (byte) (e >>> (8 * (3 - i)));
        return crc8(new byte[]{r[0], r[1], r[2]}) != (r[3] & 0xff);
    }
    static void count(byte[] code, long e, int w, long[] total, long[] missed) { total[w]++; if (!detects(code, e)) missed[w]++; }
}
