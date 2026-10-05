import java.math.BigDecimal;
import java.util.Random;
import java.util.stream.DoubleStream;

/** 浮点求和：表示误差、顺序、累加器饱和、补偿求和、分块归约、整数经过 double 之后的精度。全部单线程，输出确定。 */
public class SumLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static double naive(double[] a, int from, int to) { double s = 0; for (int i = from; i < to; i++) s += a[i]; return s; }

    static double kahan(double[] a) {
        double s = 0, c = 0;
        for (double x : a) { double y = x - c; double t = s + y; c = (t - s) - y; s = t; }
        return s;
    }

    static double neumaier(double[] a) {
        double s = 0, c = 0;
        for (double x : a) {
            double t = s + x;
            c += Math.abs(s) >= Math.abs(x) ? (s - t) + x : (x - t) + s;
            s = t;
        }
        return s + c;
    }

    static double pairwise(double[] a, int from, int to) {
        if (to - from <= 8) return naive(a, from, to);
        int mid = (from + to) >>> 1;
        return pairwise(a, from, mid) + pairwise(a, mid, to);
    }

    static double chunked(double[] a, int chunks) {
        double total = 0;
        int size = a.length / chunks;
        for (int c = 0; c < chunks; c++) total += naive(a, c * size, c == chunks - 1 ? a.length : (c + 1) * size);
        return total;
    }

    static BigDecimal exact(double[] a) { BigDecimal s = BigDecimal.ZERO; for (double x : a) s = s.add(new BigDecimal(x)); return s; }

    static String err(double got, BigDecimal exact) { return new BigDecimal(got).subtract(exact).doubleValue() + ""; }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));

        out("repr.point_one", "new BigDecimal(0.1) = " + new BigDecimal(0.1));
        out("repr.sum", "0.1 + 0.2 = " + (0.1 + 0.2) + "；与 0.3 相等 = " + (0.1 + 0.2 == 0.3));
        double ten = 0; for (int i = 0; i < 10; i++) ten += 0.1;
        out("repr.ten_times", "0.1 累加 10 次 = " + ten + "；与 1.0 相等 = " + (ten == 1.0));

        double big = 1e16; double bigFirst = big; for (int i = 0; i < 10; i++) bigFirst += 1.0;
        double smallFirst = 0; for (int i = 0; i < 10; i++) smallFirst += 1.0; smallFirst += big;
        out("order.ulp", "1e16 附近相邻两个 double 相差 " + Math.ulp(1e16));
        out("order.big_first", "1e16 之后加 10 个 1.0 = " + new BigDecimal(bigFirst).toPlainString());
        out("order.small_first", "先加 10 个 1.0 再加 1e16 = " + new BigDecimal(smallFirst).toPlainString());
        out("order.assoc", "(0.1+0.2)+0.3 = " + ((0.1 + 0.2) + 0.3) + "；0.1+(0.2+0.3) = " + (0.1 + (0.2 + 0.3)));

        float f = 0; for (int i = 0; i < 20_000_000; i++) f += 1.0f;
        out("float.saturate", "float 累加 2000 万个 1.0f = " + new BigDecimal(f).toPlainString() + "（2^24 = " + (1 << 24) + "）");
        float fa = 0; for (int i = 0; i < 10_000_000; i++) fa += 0.1f;
        out("float.tenth", "float 累加 1000 万个 0.1f = " + fa + "；double 累加 = " + naive(fill(10_000_000, 0.1), 0, 10_000_000));

        // 1000 万笔金额：0.01 到 9999.99 的两位小数，固定种子
        int n = 10_000_000;
        double[] a = new double[n];
        long cents = 0;
        Random r = new Random(20261005L);
        for (int i = 0; i < n; i++) { int c = 1 + r.nextInt(999_999); cents += c; a[i] = c / 100.0; }
        BigDecimal ex = exact(a);
        BigDecimal intended = BigDecimal.valueOf(cents, 2);
        out("amount.intended", "按分用 long 累加 = " + intended.toPlainString());
        out("amount.exact_of_doubles", "这些 double 的精确和与它相差 " + ex.subtract(intended).doubleValue());
        double sNaive = naive(a, 0, n);
        out("amount.naive", "逐个累加 = " + new BigDecimal(sNaive).toPlainString() + "；误差 " + err(sNaive, ex));
        out("amount.kahan", "Kahan 补偿 误差 " + err(kahan(a), ex));
        out("amount.neumaier", "Neumaier 补偿 误差 " + err(neumaier(a), ex));
        out("amount.pairwise", "两两归并 误差 " + err(pairwise(a, 0, n), ex));
        out("amount.stream", "DoubleStream.sum()（顺序流） 误差 " + err(DoubleStream.of(a).sum(), ex));
        double[] sorted = a.clone(); java.util.Arrays.sort(sorted);
        out("amount.sorted_asc", "升序排序后逐个累加 误差 " + err(naive(sorted, 0, n), ex));
        StringBuilder sb = new StringBuilder();
        java.util.Set<Double> distinct = new java.util.TreeSet<>();
        for (int chunks : new int[]{1, 2, 3, 4, 6, 8, 16}) {
            double s = chunked(a, chunks); distinct.add(s);
            sb.append(chunks).append(" 块 ").append(err(s, ex)).append("；");
        }
        out("amount.chunked", "分块各自累加再合并，误差：" + sb + "共 " + distinct.size() + " 个不同的结果");
        float fs = 0; for (double x : a) fs += (float) x;
        out("amount.float", "用 float 逐个累加 = " + new BigDecimal(fs).toPlainString());
        out("amount.rounded", "逐个累加的和保留两位小数 = " + BigDecimal.valueOf(sNaive).setScale(2, java.math.RoundingMode.HALF_UP)
                + "；与按分累加一致 = " + (BigDecimal.valueOf(sNaive).setScale(2, java.math.RoundingMode.HALF_UP).compareTo(intended) == 0));

        double[] cancel = {1.0, 1e100, 1.0, -1e100};
        out("cancel", "[1, 1e100, 1, -1e100]：逐个累加 " + naive(cancel, 0, 4) + "，Kahan " + kahan(cancel) + "，Neumaier " + neumaier(cancel)
                + "，DoubleStream.sum() " + DoubleStream.of(cancel).sum());

        long id = 9007199254740993L;
        out("long.to_double", "long " + id + " 转 double 再转回 = " + (long) (double) id + "（2^53 = " + (1L << 53) + "）");
        out("long.sum_as_double", "3 个 " + id + " 用 double 累加 = " + new BigDecimal((double) id + (double) id + (double) id).toPlainString()
                + "；用 long 累加 = " + (id * 3));
        out("bigdecimal.ctor", "new BigDecimal(0.1) 与 BigDecimal.valueOf(0.1) 相等 = " + new BigDecimal(0.1).equals(BigDecimal.valueOf(0.1))
                + "；new BigDecimal(\"1.10\").equals(new BigDecimal(\"1.1\")) = " + new BigDecimal("1.10").equals(new BigDecimal("1.1"))
                + "，compareTo = " + new BigDecimal("1.10").compareTo(new BigDecimal("1.1")));
        out("nan", "含一个 NaN 的数组求和 = " + naive(new double[]{1, Double.NaN, 2}, 0, 3) + "；NaN == NaN = " + (Double.NaN == Double.NaN)
                + "；Double.compare(0.0, -0.0) = " + Double.compare(0.0, -0.0) + "，0.0 == -0.0 = " + (0.0 == -0.0));
    }

    static double[] fill(int n, double v) { double[] a = new double[n]; java.util.Arrays.fill(a, v); return a; }
}
