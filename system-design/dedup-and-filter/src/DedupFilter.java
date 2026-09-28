import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.openjdk.jol.info.GraphLayout;

/**
 * 判存结构与敏感词过滤，输出为「键<TAB>事实」：
 * 1. 2000 万范围内标记 1000 万个整数，BitSet 与 HashSet&lt;Integer&gt; 的内存（JOL 统计对象图）；
 * 2. 100 万个 URL 的布隆过滤器，目标误判率 1% 与 0.1% 时的位数、哈希个数、实测误判率与漏判；以及同样 URL 放进 HashSet 的内存；
 * 3. 1 万个敏感词、20 万字文本，前缀树扫描与逐词 indexOf 的耗时与命中数。
 */
public class DedupFilter {

    public static void main(String[] args) {
        bitmap();
        bloom();
        sensitiveWords();
    }

    // ---------- 1. 位图 ----------

    static void bitmap() {
        Random r = new Random(1);
        BitSet bits = new BitSet(20_000_000);
        Set<Integer> set = new HashSet<>();
        while (set.size() < 10_000_000) {
            int x = r.nextInt(20_000_000);
            set.add(x);
            bits.set(x);
        }
        long b = GraphLayout.parseInstance(bits).totalSize();
        long h = GraphLayout.parseInstance(set).totalSize();
        out("bitmap", "2000 万范围内标记 1000 万个整数：BitSet %,d KB，HashSet<Integer> %,d KB，%.0f 倍；cardinality=%,d".formatted(
                b / 1024, h / 1024, (double) h / b, bits.cardinality()));
        long qq = 4_000_000_000L;
        out("bitmap.qq", "40 亿个号码：位图 %.2f GiB，long[] 存原始值 %.1f GiB".formatted(qq / 8 / Math.pow(1024, 3), qq * 8 / Math.pow(1024, 3)));
    }

    // ---------- 2. 布隆过滤器 ----------

    /** 按公式 m = -n·ln(p)/(ln2)²、k = (m/n)·ln2 取整，双重哈希：第 i 个位置为 h1 + i·h2。 */
    static final class Bloom {
        final long m;
        final int k;
        final long[] words;

        Bloom(long n, double p) {
            m = (long) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
            k = (int) Math.round((double) m / n * Math.log(2));
            words = new long[(int) ((m + 63) / 64)];
        }

        void add(String s) {
            long[] h = hash(s);
            for (int i = 0; i < k; i++) {
                long bit = Math.floorMod(h[0] + i * h[1], m);
                words[(int) (bit >>> 6)] |= 1L << bit;
            }
        }

        boolean mightContain(String s) {
            long[] h = hash(s);
            for (int i = 0; i < k; i++) {
                long bit = Math.floorMod(h[0] + i * h[1], m);
                if ((words[(int) (bit >>> 6)] & (1L << bit)) == 0) {
                    return false;
                }
            }
            return true;
        }

        /** 两个独立的 64 位哈希：FNV-1a 与 splitmix64 混合后的结果。 */
        static long[] hash(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            long h1 = 0xcbf29ce484222325L;
            for (byte x : b) {
                h1 = (h1 ^ (x & 0xff)) * 0x100000001b3L;
            }
            long h2 = mix(h1 ^ 0x9E3779B97F4A7C15L);
            return new long[] {mix(h1), h2 | 1};
        }

        static long mix(long z) {
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }
    }

    static void bloom() {
        int n = 1_000_000;
        List<String> urls = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            urls.add("https://example.com/item/%010d/page".formatted(i));        // 38 字节左右
        }
        for (double p : new double[] {0.01, 0.001}) {
            Bloom f = new Bloom(n, p);
            urls.forEach(f::add);
            int falseNegatives = 0;
            for (String u : urls) {
                falseNegatives += f.mightContain(u) ? 0 : 1;
            }
            int falsePositives = 0;
            int probes = 1_000_000;
            for (int i = 0; i < probes; i++) {
                falsePositives += f.mightContain("https://example.com/other/%010d/page".formatted(i)) ? 1 : 0;
            }
            out("bloom." + p, "100 万个 URL、目标误判率 %s：位数组 %,d bit（%,d KB），哈希 %d 个；100 万个未加入的 URL 误判 %,d 个（%.3f%%）；已加入的判为不存在 %d 个".formatted(
                    p, f.m, f.m / 8 / 1024, f.k, falsePositives, 100.0 * falsePositives / probes, falseNegatives));
        }
        Set<String> set = new HashSet<>(urls);
        out("bloom.hashset", "同样 100 万个 URL 放进 HashSet<String>：%,d KB".formatted(GraphLayout.parseInstance(set).totalSize() / 1024));
    }

    // ---------- 3. 敏感词 ----------

    static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        boolean end;
    }

    static void sensitiveWords() {
        Random r = new Random(7);
        char[] alphabet = new char[800];                                   // 800 个常用汉字范围内的字符
        for (int i = 0; i < alphabet.length; i++) {
            alphabet[i] = (char) (0x4e00 + i * 7);
        }
        Set<String> dict = new HashSet<>();
        while (dict.size() < 10_000) {
            int len = 3 + r.nextInt(3);
            StringBuilder w = new StringBuilder();
            for (int i = 0; i < len; i++) {
                w.append(alphabet[r.nextInt(alphabet.length)]);
            }
            dict.add(w.toString());
        }
        List<String> words = new ArrayList<>(dict);
        StringBuilder t = new StringBuilder();
        while (t.length() < 200_000) {
            if (r.nextInt(1000) == 0) {
                t.append(words.get(r.nextInt(words.size())));             // 平均每千字埋一个敏感词
            } else {
                t.append(alphabet[r.nextInt(alphabet.length)]);
            }
        }
        String text = t.toString();
        Node root = new Node();
        for (String w : words) {
            Node cur = root;
            for (char c : w.toCharArray()) {
                cur = cur.next.computeIfAbsent(c, x -> new Node());
            }
            cur.end = true;
        }
        long[] trie = new long[7];
        long[] naive = new long[7];
        int trieHits = 0;
        int naiveHits = 0;
        for (int rep = 0; rep < 7; rep++) {                                // 前 2 次为预热，取后 5 次的中位数
            long s = System.nanoTime();
            trieHits = trieScan(root, text);
            trie[rep] = System.nanoTime() - s;
            s = System.nanoTime();
            naiveHits = indexOfScan(words, text);
            naive[rep] = System.nanoTime() - s;
        }
        double tMs = median(trie) / 1e6;
        double nMs = median(naive) / 1e6;
        out("words", "1 万个敏感词、%,d 字文本：前缀树 %.1f ms、命中 %d 次；逐词 indexOf %.1f ms、命中 %d 次；相差 %.0f 倍".formatted(
                text.length(), tMs, trieHits, nMs, naiveHits, nMs / tMs));
    }

    static long median(long[] a) {
        long[] b = Arrays.copyOfRange(a, 2, a.length);
        Arrays.sort(b);
        return b[b.length / 2];
    }

    /** 每个起点向下走，命中一个词就计数并换下一个起点（与逐词统计口径一致：统计所有出现位置）。 */
    static int trieScan(Node root, String text) {
        int hits = 0;
        for (int i = 0; i < text.length(); i++) {
            Node cur = root;
            for (int j = i; j < text.length(); j++) {
                cur = cur.next.get(text.charAt(j));
                if (cur == null) {
                    break;
                }
                if (cur.end) {
                    hits++;
                }
            }
        }
        return hits;
    }

    static int indexOfScan(List<String> words, String text) {
        int hits = 0;
        for (String w : words) {
            for (int at = text.indexOf(w); at >= 0; at = text.indexOf(w, at + 1)) {
                hits++;
            }
        }
        return hits;
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
