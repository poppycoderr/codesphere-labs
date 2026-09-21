import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;

public class Facts {
    public static void main(String[] a) {
        System.out.println("JDK " + Runtime.version());
        Method[] ms = Connection.class.getMethods();
        long abs = Arrays.stream(ms).filter(m -> Modifier.isAbstract(m.getModifiers())).count();
        long def = Arrays.stream(ms).filter(Method::isDefault).count();
        System.out.println("java.sql.Connection 公共方法 " + ms.length + " 个，其中抽象 " + abs + "，default " + def);
        Arrays.stream(ms).filter(Method::isDefault).map(Method::getName).sorted().forEach(n -> System.out.println("   default: " + n));

        System.out.println("\n== 只读视图 vs 不可变集合");
        List<String> backing = new ArrayList<>(List.of("a", "b"));
        List<String> view = Collections.unmodifiableList(backing);
        List<String> copy = List.copyOf(backing);
        List<String> of = List.of("a", "b");
        backing.add("c");
        System.out.println("修改底层列表后：unmodifiableList = " + view + "，List.copyOf = " + copy);
        for (var e : Map.of("unmodifiableList", view, "List.of", of).entrySet()) {
            try { e.getValue().add("x"); } catch (UnsupportedOperationException ex) { System.out.println(e.getKey() + ".add → UnsupportedOperationException"); }
        }
        try { List.of("a", null); } catch (NullPointerException ex) { System.out.println("List.of 含 null → NullPointerException"); }
        System.out.println("Collections.unmodifiableList 允许 null：" + Collections.unmodifiableList(Arrays.asList("a", null)));
        List<String> asList = Arrays.asList("x", "y");
        asList.set(0, "z");
        System.out.println("Arrays.asList 可以 set：" + asList);
        try { asList.add("w"); } catch (UnsupportedOperationException ex) { System.out.println("Arrays.asList.add → UnsupportedOperationException（List 接口的可选操作）"); }
        System.out.println("view 的类：" + view.getClass().getName() + "；List.of 的类：" + of.getClass().getName());
    }
}
