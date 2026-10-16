import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import javax.tools.ToolProvider.*;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 包之间的依赖：v1 订单与账单互相依赖，v2 订单定义端口、账单实现它，v3 再加一个引用了订单的 common 包。
 * 每个版本先编译，再用 JDK 自带的 jdeps 列出包依赖、计算不稳定度，最后用 ArchUnit 的无环规则检查。
 */
public class ComponentLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version") + " archunit=" + JavaClasses.class.getPackage().getImplementationVersion());
        for (String v : List.of("v1", "v2", "v3")) {
            Path src = Path.of("code", v), cls = Path.of("build", "classes", v);
            Files.createDirectories(cls);
            List<String> files;
            try (Stream<Path> s = Files.walk(src)) { files = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().toList(); }
            var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
            String[] jargs = Stream.concat(Stream.of("-d", cls.toString()), files.stream()).toArray(String[]::new);
            if (javac.run(null, null, null, jargs) != 0) throw new IllegalStateException("编译失败 " + v);

            // jdeps：包级依赖
            StringWriter w = new StringWriter();
            ToolProvider.findFirst("jdeps").orElseThrow().run(new PrintWriter(w), new PrintWriter(new StringWriter()), "-verbose:package", "-filter:none", cls.toString());
            Map<String, Set<String>> edges = new TreeMap<>();
            for (String line : w.toString().split("\n")) {
                String t = line.trim();
                if (t.startsWith("shop.") && t.contains("->")) {
                    String[] parts = t.split("\\s+");
                    String from = parts[0], to = parts[2];
                    if (to.startsWith("shop.") && !from.equals(to)) edges.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
                }
            }
            Set<String> pkgs = new TreeSet<>(edges.keySet()); edges.values().forEach(pkgs::addAll);
            StringBuilder inst = new StringBuilder();
            for (String p : pkgs) {
                long ce = edges.getOrDefault(p, Set.of()).size();
                long ca = edges.values().stream().filter(s -> s.contains(p)).count();
                inst.append(p.replace("shop.", "")).append(" I=").append(ce + ca == 0 ? "-" : String.format("%.2f", (double) ce / (ce + ca))).append(" ");
            }
            out(v + ".jdeps", "包依赖 " + edges.toString().replace("shop.", "") + "；不稳定度 " + inst.toString().trim());

            // ArchUnit：包之间不得有环
            JavaClasses classes = new ClassFileImporter().importPath(cls);
            EvaluationResult r = slices().matching("shop.(*)..").should().beFreeOfCycles().evaluate(classes);
            String report = r.hasViolation() ? "违反：" + r.getFailureReport().getDetails().stream().map(x -> String.join(" ", x.lines().map(String::trim).filter(l -> !l.isEmpty()).limit(4).toList())).findFirst().orElse("") : "通过";
            out(v + ".archunit", "slices().matching(\"shop.(*)..\").should().beFreeOfCycles()：" + report);
        }
    }
}
