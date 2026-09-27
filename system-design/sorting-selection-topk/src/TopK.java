import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * 排序、选择与 Top K 的四组对照，输出为「键<TAB>事实」：
 * 1. 减法比较器在差值溢出时给出错误顺序；
 * 2. 没有同分规则时，全排序、堆、快速选择选出的前 K 条不是同一批；
 * 3. N = 100 万时，K 不同，三种做法的耗时；
 * 4. 按记录分片取 Top K 再合并是准确的；按 key 聚合计数时，各分片先取本地 Top K 会漏掉全局第一。
 */
public class TopK {

    /** 一条报名记录：分数越高越靠前，同分时先提交的靠前，再按 ID。 */
    record Signup(long id, int score, long createdAt) {
    }

    static final Comparator<Signup> BY_SCORE = Comparator.comparingInt(Signup::score).reversed();
    static final Comparator<Signup> FULL = BY_SCORE.thenComparingLong(Signup::createdAt).thenComparingLong(Signup::id);

    public static void main(String[] args) {
        overflow();
        ties();
        cost();
        shards();
    }

    // ---------- 1. 比较器溢出 ----------

    static void overflow() {
        Random r = new Random(7);
        long start = 1_767_225_600_000L;                          // 2026-01-01 00:00 UTC
        List<Signup> list = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            list.add(new Signup(i, 0, start + (long) (r.nextDouble() * 180L * 24 * 3600 * 1000)));   // 半年内的提交时间
        }
        List<Signup> bad = new ArrayList<>(list);
        String result;
        try {
            bad.sort((a, b) -> (int) (a.createdAt() - b.createdAt()));   // 差值超过 int 范围（约 24.8 天）时溢出
            result = "排序完成，相邻逆序 " + inversions(bad) + " 处";
        } catch (IllegalArgumentException e) {
            result = "抛出 IllegalArgumentException：" + e.getMessage();
        }
        List<Signup> good = new ArrayList<>(list);
        good.sort(Comparator.comparingLong(Signup::createdAt));
        System.out.println("overflow.subtract\t按提交时间排序 1 万条（跨度半年），比较器写成 (int) (a.createdAt - b.createdAt)：" + result);
        System.out.println("overflow.compare\t改用 Comparator.comparingLong：相邻逆序 " + inversions(good) + " 处");
        List<Signup> small = new ArrayList<>(list.subList(0, 20));
        small.sort((a, b) -> (int) (a.createdAt() - b.createdAt()));   // 少于 32 个元素时用插入排序，不做契约检查
        System.out.println("overflow.small\t同样的比较器只排 20 条：没有异常，相邻逆序 " + inversions(small) + " 处");
    }

    static long inversions(List<Signup> l) {
        long n = 0;
        for (int i = 1; i < l.size(); i++) if (l.get(i - 1).createdAt() > l.get(i).createdAt()) n++;
        return n;
    }

    // ---------- 2. 同分规则 ----------

    static List<Signup> data(int n, int scoreRange, long seed) {
        Random r = new Random(seed);
        long start = 1_767_225_600_000L;
        List<Signup> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(new Signup(i, r.nextInt(scoreRange), start + r.nextInt(86_400_000)));
        Collections.shuffle(l, r);
        return l;
    }

    static List<Signup> bySort(List<Signup> in, int k, Comparator<Signup> c) {
        List<Signup> copy = new ArrayList<>(in);
        copy.sort(c);
        return copy.subList(0, k);
    }

    /** 大小为 K 的堆：堆顶是当前前 K 名里最差的一个，新元素比它好就替换。 */
    static List<Signup> byHeap(List<Signup> in, int k, Comparator<Signup> c) {
        PriorityQueue<Signup> heap = new PriorityQueue<>(k, c.reversed());
        for (Signup s : in) {
            if (heap.size() < k) {
                heap.offer(s);
            } else if (c.compare(s, heap.peek()) < 0) {
                heap.poll();
                heap.offer(s);
            }
        }
        List<Signup> out = new ArrayList<>(heap);
        out.sort(c);
        return out;
    }

    /** 快速选择：把第 K 名放到位置 k-1，左边都不比它差，再对前 K 个排序。 */
    static List<Signup> bySelect(List<Signup> in, int k, Comparator<Signup> c) {
        Signup[] a = in.toArray(new Signup[0]);
        Random r = new Random(1);
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            Signup pivot = a[lo + r.nextInt(hi - lo + 1)];
            int i = lo, lt = lo, gt = hi;                     // 三路划分，大量同分时也不会退化
            while (i <= gt) {
                int cmp = c.compare(a[i], pivot);
                if (cmp < 0) swap(a, lt++, i++);
                else if (cmp > 0) swap(a, i, gt--);
                else i++;
            }
            if (k - 1 < lt) hi = lt - 1;
            else if (k - 1 > gt) lo = gt + 1;
            else break;
        }
        List<Signup> out = new ArrayList<>(Arrays.asList(a).subList(0, k));
        out.sort(c);
        return out;
    }

    static void swap(Signup[] a, int i, int j) {
        Signup t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    static String ids(List<Signup> l) {
        return Integer.toHexString(l.stream().map(Signup::id).collect(Collectors.toCollection(TreeSet::new)).hashCode());
    }

    static void ties() {
        List<Signup> in = data(1_000_000, 1000, 42);
        for (var e : List.of(Map.entry("score", BY_SCORE), Map.entry("full", FULL))) {
            List<Signup> s = bySort(in, 100, e.getValue()), h = byHeap(in, 100, e.getValue()), q = bySelect(in, 100, e.getValue());
            long boundary = s.get(99).score();
            long tied = in.stream().filter(x -> x.score() == boundary).count();
            boolean same = ids(s).equals(ids(h)) && ids(h).equals(ids(q));
            System.out.printf("ties.%s\t%s：第 100 名的分数 %d，同分记录 %d 条；全排序、堆、快速选择选出的 100 条是否相同=%s（ID 集合摘要 %s / %s / %s）%n",
                    e.getKey(), e.getKey().equals("score") ? "只按分数比较" : "分数、提交时间、ID 依次比较",
                    boundary, tied, same, ids(s), ids(h), ids(q));
        }
    }

    // ---------- 3. 成本 ----------

    static void cost() {
        List<Signup> in = data(1_000_000, 1_000_000, 7);
        for (int k : new int[] {100, 10_000, 500_000}) {
            Map<String, Function<List<Signup>, List<Signup>>> ways = new LinkedHashMap<>();
            ways.put("全排序", l -> bySort(l, k, FULL));
            ways.put("堆", l -> byHeap(l, k, FULL));
            ways.put("快速选择", l -> bySelect(l, k, FULL));
            Map<String, Long> ms = new LinkedHashMap<>();
            String check = null;
            for (var w : ways.entrySet()) {
                for (int i = 0; i < 3; i++) w.getValue().apply(in);           // 预热
                long[] t = new long[5];
                List<Signup> result = null;
                for (int i = 0; i < 5; i++) {
                    long t0 = System.nanoTime();
                    result = w.getValue().apply(in);
                    t[i] = (System.nanoTime() - t0) / 1_000_000;
                }
                Arrays.sort(t);
                ms.put(w.getKey(), t[2]);
                String sig = ids(result);
                if (check == null) check = sig;
                else if (!check.equals(sig)) throw new IllegalStateException("结果不一致：" + w.getKey());
            }
            System.out.printf("cost.%d\tN=1,000,000，K=%,d：全排序 %d ms，堆 %d ms，快速选择 %d ms（5 次中位数，三者结果相同）%n",
                    k, k, ms.get("全排序"), ms.get("堆"), ms.get("快速选择"));
        }
    }

    // ---------- 4. 分片 ----------

    static void shards() {
        // 按记录排名：每个分片取本地前 K，合并后再取前 K，结果与全局一致
        List<Signup> all = data(300_000, 1_000_000, 11);
        List<List<Signup>> parts = List.of(all.subList(0, 100_000), all.subList(100_000, 200_000), all.subList(200_000, 300_000));
        List<Signup> merged = parts.stream().flatMap(p -> byHeap(p, 100, FULL).stream()).collect(Collectors.toList());
        boolean exact = ids(bySort(merged, 100, FULL)).equals(ids(bySort(all, 100, FULL)));
        System.out.println("shards.records\t按记录排名，3 个分片各取前 100 再合并：与全局前 100 相同=" + exact);

        // 按 key 聚合计数：x 在每个分片都排第 3，三片加起来是全局第一
        List<Map<String, Integer>> counts = List.of(
                Map.of("a", 50, "b", 45, "x", 40, "c", 10),
                Map.of("d", 50, "e", 45, "x", 40, "f", 10),
                Map.of("g", 50, "h", 45, "x", 40, "i", 10));
        Map<String, Integer> global = new HashMap<>();
        counts.forEach(m -> m.forEach((k, v) -> global.merge(k, v, Integer::sum)));
        for (int local : new int[] {2, 3}) {
            Map<String, Integer> reported = new HashMap<>();
            for (var m : counts) {
                m.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(local)
                        .forEach(e -> reported.merge(e.getKey(), e.getValue(), Integer::sum));
            }
            String top = reported.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(1).map(e -> e.getKey() + "=" + e.getValue()).findFirst().orElseThrow();
            System.out.printf("shards.keys.%d\t按 key 聚合计数取第一名，每个分片上报本地前 %d：合并后第一名 %s（全局真实第一名 x=%d）%n",
                    local, local, top, global.get("x"));
        }
    }
}
