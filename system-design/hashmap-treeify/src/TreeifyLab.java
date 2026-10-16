import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Objects;

/**
 * HashMap 一个桶里冲突的键多了以后：表容量小于 64 时先扩容，达到 64 后链表转成红黑树；
 * 键可比较（Comparable）时树能按顺序查找，不可比较时退化为遍历。用 equals 的调用次数衡量查找代价，输出确定。
 * 读取 HashMap 的内部表需要 --add-opens java.base/java.util=ALL-UNNAMED。
 */
public class TreeifyLab {
    static void out(String k, Object v) { System.out.println(k + "\t" + v); }
    static long equalsCalls = 0;

    /** 所有实例的 hashCode 都相同 */
    static final class BadKey {
        final int id; BadKey(int id) { this.id = id; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { equalsCalls++; return o instanceof BadKey b && b.id == id; }
    }
    static final class BadComparableKey implements Comparable<BadComparableKey> {
        final int id; BadComparableKey(int id) { this.id = id; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { equalsCalls++; return o instanceof BadComparableKey b && b.id == id; }
        @Override public int compareTo(BadComparableKey o) { return Integer.compare(id, o.id); }
    }

    static Object[] table(HashMap<?, ?> m) throws Exception { Field f = HashMap.class.getDeclaredField("table"); f.setAccessible(true); return (Object[]) f.get(m); }
    static String binType(HashMap<?, ?> m) throws Exception {
        for (Object n : table(m)) if (n != null) return n.getClass().getSimpleName().equals("TreeNode") ? "红黑树" : "链表";
        return "空";
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        HashMap<BadKey, Integer> m = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            m.put(new BadKey(i), i);
            if (i >= 7) sb.append(i).append(" 个→表长 ").append(table(m).length).append("、").append(binType(m)).append("；");
        }
        out("treeify.threshold", "同一个桶里依次放入冲突的键：" + sb.toString().replaceAll("；$", ""));
        HashMap<BadKey, Integer> big = new HashMap<>(128);
        for (int i = 1; i <= 8; i++) big.put(new BadKey(i), i);
        out("treeify.presized", "初始容量 128 的表放入 8 个冲突的键：表长 " + table(big).length + "、" + binType(big));

        int n = 1000;
        HashMap<BadKey, Integer> plain = new HashMap<>();
        HashMap<BadComparableKey, Integer> comparable = new HashMap<>();
        for (int i = 0; i < n; i++) { plain.put(new BadKey(i), i); comparable.put(new BadComparableKey(i), i); }
        equalsCalls = 0; for (int i = 0; i < n; i++) plain.get(new BadKey(i));
        long plainCalls = equalsCalls;
        equalsCalls = 0; for (int i = 0; i < n; i++) comparable.get(new BadComparableKey(i));
        long cmpCalls = equalsCalls;
        equalsCalls = 0; plain.get(new BadKey(n + 1));
        long missPlain = equalsCalls;
        equalsCalls = 0; comparable.get(new BadComparableKey(n + 1));
        long missCmp = equalsCalls;
        out("lookup.non_comparable", n + " 个 hashCode 全相同、不可比较的键（" + binType(plain) + "）：逐个查找共调用 equals " + plainCalls + " 次，平均 " + plainCalls / n + " 次；查一个不存在的键 " + missPlain + " 次");
        out("lookup.comparable", "同样的键实现 Comparable（" + binType(comparable) + "）：共调用 equals " + cmpCalls + " 次，平均 " + cmpCalls / n + " 次；查一个不存在的键 " + missCmp + " 次");
        long chain = 0; for (int i = 0; i < n; i++) chain += i + 1;
        out("lookup.chain_estimate", "如果一直是链表（JDK 8 之前），逐个查找的 equals 次数为 1+2+…+" + n + " = " + chain + "，平均 " + chain / n + " 次");
    }
}
