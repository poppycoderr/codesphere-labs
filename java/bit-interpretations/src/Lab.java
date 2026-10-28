import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 同一串位的几种解释：补码加法与无符号加法得到同样的位，差别出在扩展、比较、除法、打印、缩窄和移位。
 * 每个场景输出一行「键<TAB>事实」。
 */
public class Lab {

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static String hex8(int v) {
        return String.format("0x%02X", v & 0xFF);
    }

    static String err(Runnable r) {
        try {
            r.run();
            return "无异常";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + "：" + e.getMessage();
        }
    }

    /** 8 位加法的进位（无符号结果超出 255）与溢出（两个同号数相加得到异号结果）。 */
    static String flags(int a, int b) {
        int ua = a & 0xFF, ub = b & 0xFF;
        int sum = (ua + ub) & 0xFF;
        boolean carry = ua + ub > 0xFF;
        boolean overflow = ((ua ^ sum) & (ub ^ sum) & 0x80) != 0;
        return hex8(a) + " + " + hex8(b) + " = " + hex8(sum) + "（无符号 " + ua + " + " + ub + " → " + sum + "，有符号 " + (byte) a + " + " + (byte) b + " → "
                + (byte) sum + "）进位 " + (carry ? 1 : 0) + "，溢出 " + (overflow ? 1 : 0);
    }

    public static void main(String[] args) {
        // 一、同一个字节，两种读法；加法的位相同
        byte ff = (byte) 0xFF;
        out("byte.ff", "(byte) 0xFF = " + ff + "；Byte.toUnsignedInt = " + Byte.toUnsignedInt(ff) + "；ff == 0xFF：" + (ff == 0xFF));
        byte[] packet = {(byte) 0x80, (byte) 0xFF, 0x10, 0x01};
        int signedSum = 0, unsignedSum = 0;
        for (byte b : packet) {
            signedSum += b;
            unsignedSum += b & 0xFF;
        }
        out("sum.bits", "字节 80 FF 10 01：按有符号累加 " + signedSum + "，按无符号累加 " + unsignedSum + "；两者低 8 位 " + hex8(signedSum) + " / " + hex8(unsignedSum)
                + "；signedSum % 256 = " + (signedSum % 256) + "，Math.floorMod = " + Math.floorMod(signedSum, 256));

        // 二、扩展：byte 参与运算先提升为 int，符号位被复制
        byte hi = 0x01, lo = (byte) 0xF0;
        int naive = (hi << 8) | lo;
        int masked = ((hi & 0xFF) << 8) | (lo & 0xFF);
        out("u16.parse", "大端字节 01 F0 拼成 16 位无符号数：(hi << 8) | lo = " + naive + "（0x" + Integer.toHexString(naive).toUpperCase() + "），((hi & 0xFF) << 8) | (lo & 0xFF) = " + masked);
        ByteBuffer buf = ByteBuffer.wrap(new byte[] {(byte) 0xFF, (byte) 0xFE});
        short s = buf.getShort(0);
        out("u16.buffer", "ByteBuffer.getShort(FF FE) = " + s + "，Short.toUnsignedInt = " + Short.toUnsignedInt(s) + "；char 是无符号的：(int) (char) s = " + (int) (char) s);
        byte nib = (byte) 0xF0;
        out("shift.byte", "取 0xF0 的高 4 位：(byte) (b >>> 4) = " + (byte) (nib >>> 4) + "，(b & 0xFF) >>> 4 = " + ((nib & 0xFF) >>> 4) + "；b >> 4 = " + (nib >> 4));
        out("shift.int", "-16 >> 2 = " + (-16 >> 2) + "，-16 >>> 2 = " + (-16 >>> 2) + "；1 << 32 = " + (1 << 32) + "，1L << 64 = " + (1L << 64) + "（移位数只取低 5/6 位）");
        byte[] le = {0x01, 0x00, 0x00, 0x00};
        out("endian", "字节 01 00 00 00：默认（大端）getInt = " + ByteBuffer.wrap(le).getInt() + "，小端 getInt = " + ByteBuffer.wrap(le).order(ByteOrder.LITTLE_ENDIAN).getInt());

        // 三、进位与溢出
        out("flag.7f01", flags(0x7F, 0x01));
        out("flag.ff01", flags(0xFF, 0x01));
        out("flag.8080", flags(0x80, 0x80));
        out("flag.0101", flags(0x01, 0x01));
        out("int.wrap", "Integer.MAX_VALUE + 1 = " + (Integer.MAX_VALUE + 1) + "；Math.addExact：" + err(() -> Math.addExact(Integer.MAX_VALUE, 1)));
        int ua = 0xFFFFFFF0, ub = 0x20;
        int us = ua + ub;
        out("int.carry", "无符号 4294967280 + 32：结果 " + Integer.toUnsignedString(us) + "，Integer.compareUnsigned(sum, a) < 0 判断出进位：" + (Integer.compareUnsigned(us, ua) < 0)
                + "；Math.addExact 不报错（有符号 -16 + 32 没有溢出）：" + err(() -> Math.addExact(ua, ub)));
        out("neg.min", "-Integer.MIN_VALUE = " + (-Integer.MIN_VALUE) + "；Integer.MIN_VALUE / -1 = " + (Integer.MIN_VALUE / -1) + "；Math.negateExact：" + err(() -> Math.negateExact(Integer.MIN_VALUE)));

        // 四、无符号的比较、除法、打印与解析
        int all = 0xFFFFFFFF;
        out("cmp.unsigned", "0xFFFFFFFF 与 1：有符号比较 " + Integer.compare(all, 1) + "，Integer.compareUnsigned " + Integer.compareUnsigned(all, 1));
        out("div.unsigned", "0xFFFFFFFF / 2：有符号 " + (all / 2) + "，Integer.divideUnsigned " + Integer.divideUnsigned(all, 2) + "；% 10：有符号 " + (all % 10) + "，remainderUnsigned "
                + Integer.remainderUnsigned(all, 10));
        out("str.unsigned", "打印：" + all + " / toUnsignedString " + Integer.toUnsignedString(all) + " / toHexString " + Integer.toHexString(all) + "；Long 最大无符号值打印为 "
                + Long.toUnsignedString(-1L));
        out("parse.hex", "Integer.parseInt(\"FFFFFFFF\", 16)：" + err(() -> Integer.parseInt("FFFFFFFF", 16)) + "；parseUnsignedInt = " + Integer.parseUnsignedInt("FFFFFFFF", 16)
                + "；(int) Long.parseLong = " + (int) Long.parseLong("FFFFFFFF", 16));

        // 五、相减比较：环绕的序号对，全序错
        Comparator<Integer> bySubtract = (a, b) -> a - b;
        List<Integer> xs = new ArrayList<>(List.of(5, Integer.MIN_VALUE, -1, Integer.MAX_VALUE, 0));
        xs.sort(bySubtract);
        List<Integer> ys = new ArrayList<>(xs);
        ys.sort(Integer::compare);
        out("cmp.subtract", "比较器 (a, b) -> a - b：compare(MIN_VALUE, 1) = " + bySubtract.compare(Integer.MIN_VALUE, 1) + "（判为 MIN_VALUE 更大）；排序结果 " + xs + "；Integer::compare " + ys);
        long t0 = Long.MAX_VALUE - 5;
        long t1 = t0 + 10;
        out("cmp.wrap", "计时器从 Long.MAX_VALUE-5 走了 10：t1 > t0 为 " + (t1 > t0) + "，t1 - t0 > 0 为 " + (t1 - t0 > 0) + "，经过 " + (t1 - t0));
        int seqA = Integer.MAX_VALUE - 100, seqB = Integer.MIN_VALUE + 100;
        int seqC = seqA + Integer.MIN_VALUE + 1;                    // 比 A 晚 2^31 + 1
        out("seq.wrap", "序号越过 MAX_VALUE，B 比 A 晚 201：B - A = " + (seqB - seqA) + "，判为更晚；C 比 A 晚 2^31 + 1：C - A = " + (seqC - seqA) + "，判为更早");

        // 六、缩窄
        long big = 3_000_000_000L;
        out("narrow", "(int) 3000000000L = " + (int) big + "；Math.toIntExact：" + err(() -> Math.toIntExact(big)) + "；(byte) 200 = " + (byte) 200 + "；(short) 70000 = " + (short) 70000);
    }
}
