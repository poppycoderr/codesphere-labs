import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 计数器溢出：轮询下标、哈希取模里的 Math.abs 与 % 在溢出和负数时的行为 */
public class Lab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static String pickNaive(List<String> nodes, AtomicInteger counter) {
        return nodes.get(Math.abs(counter.incrementAndGet()) % nodes.size());
    }
    static String pickFloorMod(List<String> nodes, AtomicInteger counter) {
        return nodes.get(Math.floorMod(counter.incrementAndGet(), nodes.size()));
    }
    static String attempt(java.util.function.Supplier<String> s) {
        try { return s.get(); } catch (RuntimeException e) { return e.getClass().getSimpleName() + "：" + e.getMessage(); }
    }

    public static void main(String[] args) {
        out("abs.min", "Math.abs(Integer.MIN_VALUE) = " + Math.abs(Integer.MIN_VALUE) + "；Math.abs(Long.MIN_VALUE) = " + Math.abs(Long.MIN_VALUE));
        out("abs.exact", "Math.absExact(Integer.MIN_VALUE)：" + attempt(() -> String.valueOf(Math.absExact(Integer.MIN_VALUE))));
        out("rem.negative", "-7 % 3 = " + (-7 % 3) + "，Math.floorMod(-7, 3) = " + Math.floorMod(-7, 3));

        List<String> nodes = List.of("n0", "n1", "n2");
        AtomicInteger c1 = new AtomicInteger(Integer.MAX_VALUE - 2);
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < 4; i++) seq.append(attempt(() -> pickNaive(nodes, c1))).append(i < 3 ? "，" : "");
        out("rr.naive", "计数器从 MAX_VALUE-2 开始，Math.abs(n) % 3 连取 4 次：" + seq);
        AtomicInteger c2 = new AtomicInteger(Integer.MAX_VALUE - 2);
        StringBuilder seq2 = new StringBuilder();
        for (int i = 0; i < 6; i++) seq2.append(pickFloorMod(nodes, c2)).append(i < 5 ? "，" : "");
        out("rr.floormod", "同样起点，Math.floorMod(n, 3) 连取 6 次：" + seq2 + "（溢出处连续两次选中 n1，分配有一次不均，但不会越界）");
        AtomicInteger c3 = new AtomicInteger(Integer.MAX_VALUE - 2);
        StringBuilder seq3 = new StringBuilder();
        for (int i = 0; i < 6; i++) seq3.append(nodes.get((c3.incrementAndGet() & Integer.MAX_VALUE) % 3)).append(i < 5 ? "，" : "");
        out("rr.mask", "同样起点，(n & Integer.MAX_VALUE) % 3 连取 6 次：" + seq3);

        String s = "polygenelubricants";
        out("hash.min", "\"" + s + "\".hashCode() = " + s.hashCode() + "；Math.abs(hash) % 10 = " + (Math.abs(s.hashCode()) % 10) + "；Math.floorMod(hash, 10) = " + Math.floorMod(s.hashCode(), 10));
        int negatives = 0, total = 0;
        for (int i = 0; i < 100_000; i++) { total++; if ((("key-" + i).hashCode() % 16) < 0) negatives++; }
        out("hash.negative", "\"key-0\"…\"key-99999\" 的 hashCode() % 16 为负数的有 " + negatives + " 个（共 " + total + " 个）");

        long perSecond = 10_000;
        double days = (double) Integer.MAX_VALUE / perSecond / 86400;
        out("time", String.format("每秒 %d 次自增，从 0 到 Integer.MAX_VALUE 需要约 %.1f 天；换成 long 约 %.0f 万年", perSecond, days, (double) Long.MAX_VALUE / perSecond / 86400 / 365 / 10_000));
    }
}
