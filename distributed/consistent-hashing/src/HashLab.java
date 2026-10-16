import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把 10 万个键分配到 10 个节点，再加入第 11 个节点：取模、哈希环、虚拟节点、有界负载四种做法下迁移了多少键、负载有多不均；
 * 以及一个占总请求 20% 的热点键。哈希用 MD5 的前 8 字节，输入固定，输出确定。
 */
public class HashLab {
    static void out(String k, Object v) { System.out.println(k + "\t" + v); }
    static long h(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (d[i] & 0xff);
            return v;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static final int KEYS = 100_000;
    static List<String> keys() { List<String> k = new ArrayList<>(); for (int i = 0; i < KEYS; i++) k.add("user:" + i); return k; }

    interface Placement { String node(String key); }
    static Placement modulo(int n) { return k -> "n" + Math.floorMod(h(k), n); }
    static Placement ring(int n, int vnodes) {
        TreeMap<Long, String> r = new TreeMap<>();
        for (int i = 0; i < n; i++) for (int v = 0; v < vnodes; v++) r.put(h("n" + i + "#" + v), "n" + i);
        return k -> { var e = r.ceilingEntry(h(k)); return (e == null ? r.firstEntry() : e).getValue(); };
    }
    /** 有界负载：每个节点最多承担 ceil(c × 平均) 个键，满了顺着环找下一个 */
    static Map<String, String> bounded(List<String> keys, int n, int vnodes, double c) {
        TreeMap<Long, String> r = new TreeMap<>();
        for (int i = 0; i < n; i++) for (int v = 0; v < vnodes; v++) r.put(h("n" + i + "#" + v), "n" + i);
        int cap = (int) Math.ceil(c * keys.size() / n);
        Map<String, Integer> load = new HashMap<>(); Map<String, String> m = new HashMap<>();
        for (String k : keys) {
            Long pos = r.ceilingKey(h(k)); if (pos == null) pos = r.firstKey();
            while (load.getOrDefault(r.get(pos), 0) >= cap) { pos = r.higherKey(pos); if (pos == null) pos = r.firstKey(); }
            m.put(k, r.get(pos)); load.merge(r.get(pos), 1, Integer::sum);
        }
        return m;
    }
    static Map<String, String> assign(List<String> keys, Placement p) { Map<String, String> m = new HashMap<>(); for (String k : keys) m.put(k, p.node(k)); return m; }
    static String moved(Map<String, String> a, Map<String, String> b) {
        long n = a.keySet().stream().filter(k -> !a.get(k).equals(b.get(k))).count();
        return String.format("%.1f%%", 100.0 * n / a.size());
    }
    static String imbalance(Map<String, String> m, int n) {
        Map<String, Integer> load = new HashMap<>(); m.values().forEach(v -> load.merge(v, 1, Integer::sum));
        int max = load.values().stream().mapToInt(i -> i).max().orElse(0), min = load.values().stream().mapToInt(i -> i).min().orElse(0);
        return String.format("最多 %d、最少 %d，最多是平均的 %.2f 倍", max, min, max / ((double) m.size() / n));
    }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version") + " keys=" + KEYS);
        List<String> ks = keys();
        var m10 = assign(ks, modulo(10)); var m11 = assign(ks, modulo(11));
        out("modulo", "取模：10 个节点 " + imbalance(m10, 10) + "；加入第 11 个节点后迁移 " + moved(m10, m11));
        var r10 = assign(ks, ring(10, 1)); var r11 = assign(ks, ring(11, 1));
        out("ring.1", "哈希环（每个节点 1 个点）：" + imbalance(r10, 10) + "；加节点迁移 " + moved(r10, r11) + "（理想值 9.1%）");
        for (int v : new int[] { 10, 100, 1000 }) {
            var a = assign(ks, ring(10, v)); var b = assign(ks, ring(11, v));
            out("ring." + v, "虚拟节点 " + v + " 个：" + imbalance(a, 10) + "；加节点迁移 " + moved(a, b));
        }
        for (double c : new double[] { 1.25, 1.10 }) {
            var b10 = bounded(ks, 10, 10, c); var b11 = bounded(ks, 11, 10, c);
            out("bounded." + c, "虚拟节点 10 个 + 有界负载 c=" + c + "：" + imbalance(b10, 10) + "；加节点迁移 " + moved(b10, b11));
        }

        // 热点：一个键占总请求的 20%，其余请求均匀分布在其他键上
        var p = ring(10, 100);
        Map<String, Double> req = new HashMap<>();
        for (String k : ks) req.merge(p.node(k), 0.8 / KEYS, Double::sum);
        String hotNode = p.node("user:42");
        req.merge(hotNode, 0.2, Double::sum);
        double max = req.values().stream().mapToDouble(d -> d).max().orElse(0);
        out("hotkey", String.format("一个键占 20%% 的请求：它所在节点承担 %.1f%% 的请求，是平均的 %.2f 倍；虚拟节点和有界负载按键的个数均衡，挡不住单个键的热度", 100 * max, max / 0.1));
    }
}
