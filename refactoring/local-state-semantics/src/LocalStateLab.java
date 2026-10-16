import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * 把「先 new 一个空集合再逐个 add」改成一行字面量、把可变变量改成 final、把 null 改成 Optional：
 * 这些看起来只是换写法的改动，各自改变了什么契约。
 */
public class LocalStateLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "抛出 " + e.getClass().getSimpleName(); } }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、几种「列表」的契约
        out("list.add", "add 一个元素：ArrayList " + attempt(() -> new ArrayList<>(List.of("a")).add("b")) + "；List.of " + attempt(() -> List.of("a").add("b"))
                + "；Arrays.asList " + attempt(() -> Arrays.asList("a").add("b")) + "；Stream.toList() " + attempt(() -> Stream.of("a").toList().add("b"))
                + "；Collectors.toList() " + attempt(() -> Stream.of("a").collect(Collectors.toList()).add("b")));
        out("list.set", "set(0, x)：Arrays.asList " + attempt(() -> Arrays.asList("a").set(0, "x")) + "；List.of " + attempt(() -> List.of("a").set(0, "x")));
        out("list.null_element", "放入 null：ArrayList " + attempt(() -> new ArrayList<>(Arrays.asList("a", null)).size()) + "；List.of(\"a\", null) " + attempt(() -> List.of("a", null))
                + "；Stream.of(\"a\", null).toList() " + attempt(() -> Stream.of("a", null).toList()) + "；List.copyOf(含 null 的列表) " + attempt(() -> List.copyOf(Arrays.asList("a", null))));
        out("list.contains_null", "查询 contains(null)：ArrayList " + attempt(() -> new ArrayList<>(List.of("a")).contains(null)) + "；List.of " + attempt(() -> List.of("a").contains(null)));
        List<String> source = new ArrayList<>(List.of("a"));
        List<String> view = Collections.unmodifiableList(source), copy = List.copyOf(source);
        source.add("b");
        out("list.view_vs_copy", "源列表加入 b 之后：unmodifiableList 视图看到 " + view + "，List.copyOf 快照仍是 " + copy);

        // 二、final 管的是变量，不是对象
        final List<String> items = new ArrayList<>();
        items.add("x");
        out("final.reference", "final List 变量照样能 add：" + items);
        record Basket(List<String> lines) { }
        List<String> lines = new ArrayList<>(List.of("apple"));
        Basket basket = new Basket(lines);
        lines.add("pear");
        out("final.record", "record 的字段是 final 的，构造后修改传入的列表：record 里看到 " + basket.lines());

        // 三、声明与赋值分开：编译器检查每条路径都赋了值
        out("definite.ok", "final int fee; if/else 两个分支各赋一次值：" + compile("class T { int f(boolean b) { final int fee; if (b) fee = 1; else fee = 2; return fee; } }"));
        out("definite.missing", "只在 if 分支赋值：" + compile("class T { int f(boolean b) { final int fee; if (b) fee = 1; return fee; } }"));
        out("definite.placeholder", "先给占位值 0 再按条件改：" + compile("class T { int f(boolean b) { int fee = 0; if (b) fee = 1; return fee; } }") + "（漏掉的分支返回 0，编译器不再提醒）");

        // 四、var 与菱形一起用
        out("var.diamond", "var list = new ArrayList<>(); 推断出的类型：" + compileType());

        // 五、Optional 只是把「可能没有」写进类型
        out("optional.get", "Optional.empty().get()：" + attempt(() -> Optional.empty().get()) + "；Optional.of(null)：" + attempt(() -> Optional.of(null)) + "；Optional.ofNullable(null).orElse(\"默认\")：" + Optional.ofNullable(null).orElse("默认"));
    }

    static String compile(String source) {
        DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
        JavaFileObject f = new SimpleJavaFileObject(URI.create("string:///T.java"), JavaFileObject.Kind.SOURCE) { @Override public CharSequence getCharContent(boolean b) { return source; } };
        boolean ok = ToolProvider.getSystemJavaCompiler().getTask(null, null, d, List.of("-proc:none", "-d", System.getProperty("java.io.tmpdir")), null, List.of(f)).call();
        return ok ? "编译通过" : "编译失败（" + d.getDiagnostics().get(0).getMessage(Locale.ROOT) + "）";
    }
    static String compileType() {
        String src = "import java.util.*; class T { void f() { var list = new ArrayList<>(); list.add(1); list.add(\"x\"); String s = list.get(0); } }";
        return compile(src).replace("编译失败（", "ArrayList<Object>：放进 1 和 \"x\" 都能编译，取出时 ").replace("）", "");
    }
}
