import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * 金额计算里差出来的那一分钱：小数从哪里构造、按什么规则舍入、先舍入还是先求和、一笔钱怎么拆给多方。
 * 全部是确定性计算，输出逐行固定。
 */
public class MoneyLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static final RoundingMode HU = RoundingMode.HALF_UP;

    /** 最大余数法：先各自向下取整，剩下的最小单位按余数从大到小每人补 1；余数相同按原顺序 */
    static long[] allocate(long totalMinor, long[] weights) {
        long sum = Arrays.stream(weights).sum(), given = 0;
        long[] share = new long[weights.length], rem = new long[weights.length];
        for (int i = 0; i < weights.length; i++) {
            share[i] = Math.floorDiv(Math.multiplyExact(totalMinor, weights[i]), sum);
            rem[i] = Math.floorMod(Math.multiplyExact(totalMinor, weights[i]), sum);
            given += share[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) order.add(i);
        order.sort(Comparator.comparingLong((Integer i) -> -rem[i]).thenComparingInt(i -> i));
        for (int k = 0; k < totalMinor - given; k++) share[order.get(k)]++;
        return share;
    }
    static String cents(long[] minor) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < minor.length; i++) sb.append(i > 0 ? ", " : "").append(BigDecimal.valueOf(minor[i], 2));
        return sb.append("]，合计 ").append(BigDecimal.valueOf(Arrays.stream(minor).sum(), 2)).toString();
    }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、小数从哪里构造
        out("construct.from_double", "new BigDecimal(0.1) = " + new BigDecimal(0.1));
        out("construct.from_string", "new BigDecimal(\"0.1\") = " + new BigDecimal("0.1"));
        out("construct.value_of", "BigDecimal.valueOf(0.1) = " + BigDecimal.valueOf(0.1));
        out("construct.2_675", "new BigDecimal(2.675) = " + new BigDecimal(2.675));

        // 二、同一个 2.675 保留两位，五种写法
        out("round.bigdecimal_from_double", "new BigDecimal(2.675).setScale(2, HALF_UP) = " + new BigDecimal(2.675).setScale(2, HU));
        out("round.bigdecimal_from_string", "new BigDecimal(\"2.675\").setScale(2, HALF_UP) = " + bd("2.675").setScale(2, HU));
        out("round.math_round", "Math.round(2.675 * 100) / 100.0 = " + Math.round(2.675 * 100) / 100.0 + "（2.675 * 100 = " + 2.675 * 100 + "）");
        out("round.math_round_1_005", "Math.round(1.005 * 100) / 100.0 = " + Math.round(1.005 * 100) / 100.0 + "（1.005 * 100 = " + 1.005 * 100 + "）");
        out("round.string_format", "String.format(\"%.2f\", 2.675) = " + String.format(Locale.ROOT, "%.2f", 2.675));
        out("round.decimal_format", "new DecimalFormat(\"0.00\").format(2.675) = " + new DecimalFormat("0.00").format(2.675));
        out("round.decimal_format_default", "DecimalFormat 的默认舍入方式 = " + new DecimalFormat("0.00").getRoundingMode()
                + "；format(0.125) = " + new DecimalFormat("0.00").format(0.125) + "，format(0.375) = " + new DecimalFormat("0.00").format(0.375));

        // 三、浮点金额转成分
        out("to_minor.cast", "(long) (19.99 * 100) = " + (long) (19.99 * 100) + "（19.99 * 100 = " + 19.99 * 100 + "）");
        out("to_minor.cast_more", "(long) (0.29 * 100) = " + (long) (0.29 * 100) + "，(long) (4.35 * 100) = " + (long) (4.35 * 100) + "，(long) (1.15 * 100) = " + (long) (1.15 * 100));
        int wrong = 0;
        for (int c = 0; c < 10000; c++) if ((long) (Double.parseDouble(c / 100 + "." + String.format("%02d", c % 100)) * 100) != c) wrong++;
        out("to_minor.cast_scan", "0.00 到 99.99 这一万个金额里，强制转换得到的分数不对的有 " + wrong + " 个");
        out("to_minor.exact", "new BigDecimal(\"19.99\").movePointRight(2).longValueExact() = " + bd("19.99").movePointRight(2).longValueExact());

        // 四、舍入方式
        String[] inputs = {"2.5", "3.5", "-2.5", "2.1", "-2.1"};
        for (RoundingMode m : new RoundingMode[]{RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.HALF_DOWN, RoundingMode.UP, RoundingMode.DOWN, RoundingMode.CEILING, RoundingMode.FLOOR}) {
            StringBuilder sb = new StringBuilder();
            for (String in : inputs) sb.append(in).append(" → ").append(bd(in).setScale(0, m)).append("  ");
            out("mode." + m, sb.toString().trim());
        }
        BigDecimal exact = BigDecimal.ZERO, halfUp = BigDecimal.ZERO, halfEven = BigDecimal.ZERO;
        for (int i = 0; i < 1000; i++) {
            BigDecimal v = BigDecimal.valueOf(i * 10L + 5, 3);                       // 0.005、0.015、……、9.995
            exact = exact.add(v); halfUp = halfUp.add(v.setScale(2, HU)); halfEven = halfEven.add(v.setScale(2, RoundingMode.HALF_EVEN));
        }
        out("bias", "1000 笔恰好落在半分上的金额：精确合计 " + exact + "，逐笔 HALF_UP 后合计 " + halfUp + "（多 " + halfUp.subtract(exact) + "），逐笔 HALF_EVEN 后合计 " + halfEven + "（多 " + halfEven.subtract(exact) + "）");

        // 五、除法、精度与输出
        try { bd("1").divide(bd("3")); } catch (ArithmeticException e) { out("divide.no_scale", "new BigDecimal(\"1\").divide(new BigDecimal(\"3\")) 抛出 ArithmeticException：" + e.getMessage()); }
        out("divide.terminating", "1 ÷ 4 不指定精度 = " + bd("1").divide(bd("4")) + "（除得尽就不报错）");
        out("divide.scale_of_dividend", "new BigDecimal(\"10\").divide(new BigDecimal(\"3\"), HALF_UP) = " + bd("10").divide(bd("3"), HU) + "；被除数写成 \"10.00\" = " + bd("10.00").divide(bd("3"), HU));
        out("divide.math_context", "new BigDecimal(\"1234.567\").round(new MathContext(2)) = " + bd("1234.567").round(new MathContext(2)) + "（MathContext 的 2 是有效数字，不是小数位）");
        BigDecimal stripped = bd("100.00").stripTrailingZeros();
        out("print.strip", "new BigDecimal(\"100.00\").stripTrailingZeros() 的 toString = " + stripped + "，toPlainString = " + stripped.toPlainString());

        // 六、先舍入还是先求和
        BigDecimal rate = bd("0.0825"), price = bd("0.99");
        BigDecimal perLine = price.multiply(rate).setScale(2, HU).multiply(bd("3"));
        BigDecimal onTotal = price.multiply(bd("3")).multiply(rate).setScale(2, HU);
        out("order.tax", "3 件 0.99 的商品，税率 8.25%：逐行算税再相加 = " + perLine + "，按合计算税 = " + onTotal);

        // 七、一笔钱拆给多方
        BigDecimal third = bd("100.00").divide(bd("3"), 2, HU);
        out("split.equal", "100.00 平分给 3 方，各自舍入 = " + third + "，三份合计 " + third.multiply(bd("3")));
        BigDecimal tenth = bd("0.05").divide(bd("10"), 2, HU);
        out("split.tiny", "0.05 平分给 10 方，各自 HALF_UP = " + tenth + "，十份合计 " + tenth.multiply(bd("10")));
        out("split.last_takes_rest", "同上，前 9 份各自舍入、最后一份拿余数：最后一份 = " + bd("0.05").subtract(tenth.multiply(bd("9"))));
        BigDecimal[] ratio = {bd("0.5"), bd("0.3"), bd("0.2")};
        BigDecimal ratioSum = BigDecimal.ZERO; StringBuilder parts = new StringBuilder();
        for (BigDecimal r : ratio) { BigDecimal p = bd("0.05").multiply(r).setScale(2, HU); ratioSum = ratioSum.add(p); parts.append(p).append(" "); }
        out("split.ratio", "0.05 按 5:3:2 拆，各自 HALF_UP = " + parts.toString().trim() + "，合计 " + ratioSum);
        out("allocate.equal", "最大余数法，100.00 按 1:1:1 = " + cents(allocate(10000, new long[]{1, 1, 1})));
        out("allocate.tiny", "最大余数法，0.05 按十个 1 = " + cents(allocate(5, new long[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1})));
        out("allocate.ratio", "最大余数法，0.05 按 5:3:2 = " + cents(allocate(5, new long[]{5, 3, 2})));

        // 八、优惠分摊与分次退款
        long[] prices = {3333, 3333, 3333}; long paid = 9999 - 1000;
        BigDecimal refundSum = BigDecimal.ZERO; StringBuilder each = new StringBuilder();
        for (long p : prices) {
            BigDecimal r = BigDecimal.valueOf(paid, 2).multiply(BigDecimal.valueOf(p)).divide(BigDecimal.valueOf(9999), 2, HU);
            refundSum = refundSum.add(r); each.append(r).append(" ");
        }
        out("refund.on_demand", "3 件 33.33 的商品用了 10.00 的优惠，实付 89.99；退款时按比例现算每件 = " + each.toString().trim() + "，三件都退合计 " + refundSum);
        out("refund.allocated", "下单时用最大余数法把实付分到每件并保存 = " + cents(allocate(paid, prices)));

        // 九、最小货币单位不都是两位
        for (String code : new String[]{"USD", "CNY", "JPY", "KRW", "KWD", "BHD", "CLF"}) {
            int digits = Currency.getInstance(code).getDefaultFractionDigits();
            out("currency." + code, "小数位 " + digits + "；数据库里存的 1000 个最小单位 = " + BigDecimal.valueOf(1000, digits).toPlainString() + " " + code);
        }
    }
}
