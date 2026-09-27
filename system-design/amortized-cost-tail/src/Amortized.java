import java.util.*;

/**
 * 均摊 O(1) 与单次尖峰、小 N 下顺序扫描与哈希查找的交叉点，输出为「键<TAB>事实」。
 * 参数选择场景：list（ArrayList 扩容）、map（HashMap rehash）、lookup（交叉点）。
 * list 与 map 用 Epsilon GC 和预触内存运行，排除 GC 与缺页，只留下复制本身的代价。
 */
public class Amortized {

    static final int LIST_N = 10_000_000;
    static final int MAP_N = 2_000_000;
    static final int ROUNDS = 3;

    public static void main(String[] args) {
        switch (args[0]) {
            case "list" -> list();
            case "map" -> map();
            case "lookup" -> lookup(false);
            case "lookup-skewed" -> lookup(true);
            case "lookup-int" -> lookupInt();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    // ---------- 1. ArrayList 扩容 ----------

    static void list() {
        Set<Integer> growAt = new TreeSet<>();
        long copied = 0;
        for (int cap = 0, size = 0; size < LIST_N; size++) {
            if (size == cap) {                                   // 与 ArrayList.grow 相同：空列表先到 10，之后每次 1.5 倍
                growAt.add(size);
                copied += size;
                cap = cap == 0 ? 10 : cap + (cap >> 1);
            }
        }
        out("list.model", "默认容量加入 %,d 个元素：扩容 %d 次，累计复制 %,d 个引用（%.2f 倍 N）".formatted(LIST_N, growAt.size(), copied, (double) copied / LIST_N));
        Integer v = 42;
        long[] t = new long[LIST_N];
        for (int round = 1; round <= ROUNDS; round++) {
            boolean last = round == ROUNDS;
            for (boolean presized : new boolean[] {false, true}) {
                ArrayList<Integer> list = presized ? new ArrayList<>(LIST_N) : new ArrayList<>();
                long begin = System.nanoTime();
                for (int i = 0; i < LIST_N; i++) {
                    long s = System.nanoTime();
                    list.add(v);
                    t[i] = System.nanoTime() - s;
                }
                long total = System.nanoTime() - begin;
                if (last) {
                    report(presized ? "list.presized" : "list.default", t, total, presized ? Set.of() : growAt);
                }
            }
        }
    }

    // ---------- 2. HashMap rehash ----------

    static void map() {
        Set<Integer> growAt = new TreeSet<>();
        long moved = 0;
        for (int cap = 16, size = 0; size < MAP_N; size++) {
            if (size + 1 > cap * 3 / 4) {                         // 与 HashMap.putVal 相同：++size > threshold 时 resize
                growAt.add(size);
                moved += size + 1;
                cap <<= 1;
            }
        }
        out("map.model", "默认容量放入 %,d 个键：rehash %d 次，累计搬移 %,d 个节点（%.2f 倍 N）".formatted(MAP_N, growAt.size(), moved, (double) moved / MAP_N));
        Integer[] keys = new Integer[MAP_N];
        for (int i = 0; i < MAP_N; i++) {
            keys[i] = i * 7919;                                  // 提前装箱，不把 Integer 分配算进 put
        }
        long[] t = new long[MAP_N];
        for (int round = 1; round <= ROUNDS; round++) {
            boolean last = round == ROUNDS;
            for (boolean presized : new boolean[] {false, true}) {
                HashMap<Integer, Integer> map = presized ? HashMap.newHashMap(MAP_N) : new HashMap<>();
                long begin = System.nanoTime();
                for (int i = 0; i < MAP_N; i++) {
                    long s = System.nanoTime();
                    map.put(keys[i], keys[i]);
                    t[i] = System.nanoTime() - s;
                }
                long total = System.nanoTime() - begin;
                if (last) {
                    report(presized ? "map.presized" : "map.default", t, total, presized ? Set.of() : growAt);
                }
            }
        }
    }

    /** 均值、分位数、最大值，以及最慢 10 次里有几次正好落在扩容点上。 */
    static void report(String key, long[] t, long total, Set<Integer> growAt) {
        int n = t.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Long.compare(t[b], t[a]));
        long[] sorted = t.clone();
        Arrays.sort(sorted);
        int onGrow = 0;
        StringJoiner top = new StringJoiner(", ");
        for (int k = 0; k < 10; k++) {
            int i = idx[k];
            boolean hit = growAt.contains(i);
            onGrow += hit ? 1 : 0;
            top.add("#%,d=%.2fms%s".formatted(i, t[i] / 1e6, hit ? "*" : ""));
        }
        long over100us = Arrays.stream(t).filter(x -> x >= 100_000).count();
        out(key, "总耗时 %.1f ms，均值 %.1f ns/次，p50 %d ns，p99 %d ns，p99.99 %d ns，最大 %.2f ms，≥100µs 的操作 %d 次".formatted(
                total / 1e6, (double) total / n, sorted[n / 2], sorted[(int) (n * 0.99)], sorted[(int) (n * 0.9999)], sorted[n - 1] / 1e6, over100us));
        out(key + ".top", "最慢 10 次（*为扩容点）：" + top);
        if (!growAt.isEmpty()) {
            out(key + ".ongrow", "最慢 10 次中落在扩容点上的：%d 次".formatted(onGrow));
        }
    }

    // ---------- 3. 小 N：顺序扫描 vs 哈希查找 ----------

    static final int[] SIZES = {1, 2, 4, 8, 16, 32, 64, 128, 256};
    static final int QUERIES = 1 << 20;

    /** skewed 为 true 时 90% 的查询命中第一个键，其余均匀分布；否则全部均匀分布。 */
    static void lookup(boolean skewed) {
        Random r = new Random(11);
        for (int pass = 0; pass < 2; pass++) {                    // 第一遍预热，只输出第二遍
            for (int n : SIZES) {
                String[] keys = new String[n];
                Map<String, Integer> map = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    keys[i] = "header-%04d".formatted(i);         // 等长、公共前缀，equals 需要比较到末尾
                    map.put(keys[i], i);
                }
                String[] queries = new String[QUERIES];
                for (int q = 0; q < QUERIES; q++) {
                    int pick = skewed && r.nextInt(10) != 0 ? 0 : r.nextInt(n);
                    queries[q] = new String(keys[pick]);  // 与表中不是同一个对象，equals 不能靠引用相等短路
                    queries[q].hashCode();                        // 预先算好哈希，模拟 key 被多次使用的常见情况
                }
                long[] scan = new long[7];
                long[] hash = new long[7];
                long sink = 0;
                for (int rep = 0; rep < 7; rep++) {
                    long s = System.nanoTime();
                    for (String q : queries) {
                        sink += scan(keys, q);
                    }
                    scan[rep] = System.nanoTime() - s;
                    s = System.nanoTime();
                    for (String q : queries) {
                        sink += map.get(q);
                    }
                    hash[rep] = System.nanoTime() - s;
                }
                Arrays.sort(scan);
                Arrays.sort(hash);
                if (pass == 1) {
                    out((skewed ? "lookup-skewed." : "lookup.") + n, "N=%d：顺序扫描 %.2f ns/次，HashMap %.2f ns/次（校验和 %d）".formatted(
                            n, scan[3] / (double) QUERIES, hash[3] / (double) QUERIES, sink % 1000));
                }
            }
        }
    }

    /** 同样的对照换成 int 键：数组里逐个比较 int，HashMap 里是装箱的 Integer。 */
    static void lookupInt() {
        Random r = new Random(11);
        for (int pass = 0; pass < 2; pass++) {
            for (int n : SIZES) {
                int[] keys = new int[n];
                Map<Integer, Integer> map = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    keys[i] = 1_000 + i * 37;
                    map.put(keys[i], i);
                }
                int[] raw = new int[QUERIES];
                Integer[] boxed = new Integer[QUERIES];
                for (int q = 0; q < QUERIES; q++) {
                    raw[q] = keys[r.nextInt(n)];
                    boxed[q] = raw[q];                            // 提前装箱，不把装箱算进 HashMap 查找
                }
                long[] scan = new long[7];
                long[] hash = new long[7];
                long sink = 0;
                for (int rep = 0; rep < 7; rep++) {
                    long s = System.nanoTime();
                    for (int q : raw) {
                        sink += scan(keys, q);
                    }
                    scan[rep] = System.nanoTime() - s;
                    s = System.nanoTime();
                    for (Integer q : boxed) {
                        sink += map.get(q);
                    }
                    hash[rep] = System.nanoTime() - s;
                }
                Arrays.sort(scan);
                Arrays.sort(hash);
                if (pass == 1) {
                    out("lookup-int." + n, "N=%d：顺序扫描 %.2f ns/次，HashMap %.2f ns/次（校验和 %d）".formatted(
                            n, scan[3] / (double) QUERIES, hash[3] / (double) QUERIES, sink % 1000));
                }
            }
        }
    }

    static int scan(int[] keys, int q) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == q) {
                return i;
            }
        }
        return -1;
    }

    static int scan(String[] keys, String q) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(q)) {
                return i;
            }
        }
        return -1;
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
