import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 三套「相等」（==、equals/hashCode、compareTo）在集合里各自起作用的地方，以及集合视图与副本的区别。单线程，输出确定。 */
public class EqualityLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static String attempt(Supplier<Object> action) {
        try { return String.valueOf(action.get()); }
        catch (RuntimeException e) { return "抛出 " + e.getClass().getSimpleName(); }
    }

    static final class MutableKey {
        String id;
        MutableKey(String id) { this.id = id; }
        @Override public boolean equals(Object o) { return o instanceof MutableKey k && k.id.equals(id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    static final class NoHash {
        final String id;
        NoHash(String id) { this.id = id; }
        @Override public boolean equals(Object o) { return o instanceof NoHash n && n.id.equals(id); }
    }

    static class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) { return o instanceof Point p && p.x == x && p.y == y; }
        @Override public int hashCode() { return 31 * x + y; }
    }

    static final class ColorPoint extends Point {
        final String color;
        ColorPoint(int x, int y, String color) { super(x, y); this.color = color; }
        @Override public boolean equals(Object o) { return o instanceof ColorPoint c && super.equals(o) && c.color.equals(color); }
        @Override public int hashCode() { return 31 * super.hashCode() + color.hashCode(); }
    }

    record Payload(String name, int[] data) {}

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、三套相等
        BigDecimal a = new BigDecimal("1.0"), b = new BigDecimal("1.00");
        out("bigdecimal", "1.0 与 1.00：equals = " + a.equals(b) + "，compareTo = " + a.compareTo(b) + "；HashSet 里有 " + new HashSet<>(List.of(a, b)).size()
                + " 个，TreeSet 里有 " + new TreeSet<>(List.of(a, b)).size() + " 个；stripTrailingZeros 之后 HashSet 里有 "
                + Stream.of(a, b).map(BigDecimal::stripTrailingZeros).collect(Collectors.toSet()).size() + " 个");
        Integer i1 = 127, i2 = 127, i3 = 128, i4 = 128;
        out("boxed.identity", "Integer 127 == 127：" + (i1 == i2) + "；128 == 128：" + (i3 == i4) + "；128 equals 128：" + i3.equals(i4));
        Map<Long, String> byId = new HashMap<>();
        byId.put(1L, "alice");
        out("boxed.map_key", "Map<Long, String> 放入 1L 后，get(1) = " + byId.get(1) + "，get(1L) = " + byId.get(1L) + "；Long.valueOf(1).equals(1) = " + Long.valueOf(1).equals(1));

        Set<MutableKey> keys = new HashSet<>();
        MutableKey k = new MutableKey("a");
        keys.add(k);
        k.id = "b";
        out("mutable_key", "放进 HashSet 后修改参与 hashCode 的字段：contains(同一个对象) = " + keys.contains(k) + "，remove = " + keys.remove(k)
                + "，size = " + keys.size() + "，遍历能找到 = " + keys.stream().anyMatch(x -> x == k));

        Set<NoHash> noHash = new HashSet<>(List.of(new NoHash("a")));
        out("no_hashcode", "只重写 equals：两个相等对象 equals = " + new NoHash("a").equals(new NoHash("a")) + "，HashSet.contains(相等的另一个对象) = " + noHash.contains(new NoHash("a"))
                + "，ArrayList.contains = " + new ArrayList<>(noHash).contains(new NoHash("a")));

        Point p = new Point(1, 2); ColorPoint cp = new ColorPoint(1, 2, "red");
        out("asymmetric", "父类与子类：p.equals(cp) = " + p.equals(cp) + "，cp.equals(p) = " + cp.equals(p)
                + "；List.of(cp).contains(p) = " + List.of(cp).contains(p) + "，List.of(p).contains(cp) = " + List.of(p).contains(cp));

        TreeSet<String> ci = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ci.add("Order-1"); ci.add("ORDER-1");
        TreeMap<String, Integer> cm = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        cm.put("Order-1", 1); cm.put("ORDER-1", 2);
        out("comparator", "忽略大小写的比较器：TreeSet 加入 Order-1 与 ORDER-1 后是 " + ci + "；TreeMap 依次 put 后是 " + cm);

        out("record_array", "record 的数组分量：两个内容相同的 record equals = " + new Payload("x", new int[]{1, 2}).equals(new Payload("x", new int[]{1, 2})));

        // 二、视图与副本
        int[] prim = {1, 2, 3};
        Integer[] boxed = {1, 2, 3};
        List<Integer> view = Arrays.asList(boxed);
        view.set(0, 99);
        out("as_list", "Arrays.asList(int[]) 的 size = " + Arrays.asList(prim).size() + "；Arrays.asList(Integer[]) 上 set(0, 99) 后原数组是 " + Arrays.toString(boxed)
                + "；add(4)：" + attempt(() -> view.add(4)));

        List<Integer> parent = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        List<Integer> sub = parent.subList(1, 3);
        sub.clear();
        String afterClear = parent.toString();
        List<Integer> sub2 = parent.subList(0, 2);
        parent.add(6);
        out("sub_list", "对 subList(1, 3) 调用 clear() 后原列表是 " + afterClear + "；取了 subList 之后再修改原列表，访问 subList：" + attempt(sub2::size));

        List<String> src = new ArrayList<>(List.of("a"));
        List<String> unmodifiable = Collections.unmodifiableList(src), copy = List.copyOf(src);
        src.add("b");
        out("unmodifiable", "源列表追加元素后：unmodifiableList 看到 " + unmodifiable + "，List.copyOf 看到 " + copy + "；对 unmodifiableList 调用 add：" + attempt(() -> unmodifiable.add("c")));
        out("list_of_null", "List.of(1, 2).contains(null)：" + attempt(() -> List.of(1, 2).contains(null)) + "；Arrays.asList(1, 2).contains(null)：" + Arrays.asList(1, 2).contains(null));

        List<String> viaToList = Stream.of("a", null).toList();
        out("stream_to_list", "Stream.toList() 的结果 " + viaToList + " 调用 add：" + attempt(() -> viaToList.add("c"))
                + "；Collectors.toList() 的结果调用 add：" + attempt(() -> Stream.of("a").collect(Collectors.toList()).add("c")));
        out("to_map", "Collectors.toMap 遇到重复键：" + attempt(() -> Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> 1)))
                + "；遇到 null 值：" + attempt(() -> Stream.of("a").collect(Collectors.toMap(s -> s, s -> (Object) null))));

        Map<String, Integer> stock = new HashMap<>(Map.of("a", 1, "b", 2));
        stock.keySet().remove("a");
        out("key_set", "从 keySet() 里删除 a 之后 Map 是 " + stock);

        List<Integer> nums = new ArrayList<>(List.of(10, 20, 30, 1));
        nums.remove(1);
        String afterIndex = nums.toString();
        nums.remove(Integer.valueOf(1));
        out("remove_overload", "列表 [10, 20, 30, 1] 调用 remove(1) 后是 " + afterIndex + "；再调用 remove(Integer.valueOf(1)) 后是 " + nums);

        List<int[]> rows = new ArrayList<>(); rows.add(new int[]{1});
        List<int[]> rowsCopy = new ArrayList<>(rows);
        rowsCopy.get(0)[0] = 42;
        out("shallow_copy", "new ArrayList<>(源) 之后修改副本里的元素，源里的元素是 " + rows.get(0)[0]);
    }
}
