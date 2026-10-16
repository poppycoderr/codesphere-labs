import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 同一个报名需求的两种实现（事务脚本、领域模型）各自经历三次需求变化：v1 候补、v2 调换场次、v3 为 VIP 保留席位。
 * 对每个版本：编译、跑同一组验收场景，统计相对上一版本改动的文件数与增删行数，以及「名额条件」在代码里写了几处。
 */
public class EvolutionLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    record Scenario(String name, int since, int until, Consumer<api.Api> run) { }
    static void expect(Object actual, Object expected, String what) { if (!actual.equals(expected)) throw new AssertionError(what + "：期望 " + expected + "，实际 " + actual); }

    static List<Scenario> scenarios() {
        List<Scenario> s = new ArrayList<>();
        s.add(new Scenario("满员拒绝", 0, 0, a -> { a.createSession("X", 2); a.register("X", "a", false); a.register("X", "b", false); expect(a.register("X", "c", false), "REJECTED:已满", "c"); }));
        s.add(new Scenario("重复报名", 0, 3, a -> { a.createSession("X", 2); a.register("X", "a", false); expect(a.register("X", "a", false), "REJECTED:重复报名", "a"); }));
        s.add(new Scenario("满员进候补、取消后递补", 1, 3, a -> { a.createSession("X", 2); a.register("X", "a", false); a.register("X", "b", false);
            expect(a.register("X", "c", false), "WAITLISTED", "c"); a.cancel("X", "a"); expect(a.registered("X"), List.of("b", "c"), "名单"); expect(a.waitlist("X"), List.of(), "候补"); }));
        s.add(new Scenario("调换后原场次递补", 2, 3, a -> { a.createSession("X", 2); a.createSession("Y", 1); a.register("X", "a", false); a.register("X", "b", false); a.register("X", "c", false);
            expect(a.transfer("a", "X", "Y"), "REGISTERED", "调换"); expect(a.registered("X"), List.of("b", "c"), "X"); expect(a.registered("Y"), List.of("a"), "Y"); }));
        s.add(new Scenario("调入满员场次被拒、原报名保留", 2, 3, a -> { a.createSession("X", 2); a.createSession("Y", 1); a.register("X", "a", false); a.register("Y", "z", false);
            expect(a.transfer("a", "X", "Y"), "REJECTED:已满", "调换"); expect(a.registered("X"), List.of("a"), "X"); }));
        s.add(new Scenario("非 VIP 不占保留席位", 3, 3, a -> { a.createSession("V", 4, 2); a.register("V", "n1", false); a.register("V", "n2", false);
            expect(a.register("V", "n3", false), "WAITLISTED", "n3"); expect(a.register("V", "v1", true), "REGISTERED", "v1"); }));
        s.add(new Scenario("递补时跳过不能占位的候补", 3, 3, a -> { a.createSession("V", 3, 2); a.register("V", "n1", false); a.register("V", "n2", false); a.register("V", "v1", true); a.register("V", "v2", true); a.register("V", "v3", true);
            a.cancel("V", "v1"); expect(a.registered("V"), List.of("n1", "v2", "v3"), "名单"); expect(a.waitlist("V"), List.of("n2"), "候补"); }));
        s.add(new Scenario("非 VIP 调入时不占保留席位", 3, 3, a -> { a.createSession("X", 2); a.createSession("V", 3, 2); a.register("X", "n9", false); a.register("V", "n1", false);
            expect(a.transfer("n9", "X", "V"), "REJECTED:已满", "调换"); }));
        return s;
    }

    static List<String> lines(Path p) throws Exception { return Files.exists(p) ? Files.readAllLines(p) : List.of(); }
    /** 基于最长公共子序列的行级差异：返回 {新增, 删除} */
    static int[] diff(List<String> a, List<String> b) {
        int[][] l = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) for (int j = b.size() - 1; j >= 0; j--)
            l[i][j] = a.get(i).equals(b.get(j)) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
        int common = l[0][0];
        return new int[] { b.size() - common, a.size() - common };
    }
    static Map<String, Path> files(Path dir) throws Exception {
        Map<String, Path> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) { s.filter(p -> p.toString().endsWith(".java")).forEach(p -> m.put(dir.relativize(p).toString(), p)); }
        return m;
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        Path apiOut = Path.of("build/classes/api");
        Files.createDirectories(apiOut);
        if (javac.run(null, null, null, "-d", apiOut.toString(), "code/api/Api.java") != 0) throw new IllegalStateException("api 编译失败");
        for (String style : List.of("script", "model")) {
            for (int v = 0; v <= 3; v++) {
                Path src = Path.of("code", style, "v" + v), cls = Path.of("build/classes", style, "v" + v);
                Files.createDirectories(cls);
                List<String> a = new ArrayList<>(List.of("-d", cls.toString(), "-cp", apiOut.toString()));
                files(src).values().forEach(p -> a.add(p.toString()));
                if (javac.run(null, null, null, a.toArray(String[]::new)) != 0) throw new IllegalStateException("编译失败 " + style + " v" + v);
                int pass = 0, total = 0; List<String> failed = new ArrayList<>();
                for (Scenario sc : scenarios()) {
                    if (v < sc.since() || v > sc.until()) continue;
                    total++;
                    try (URLClassLoader cl = new URLClassLoader(new URL[] { cls.toUri().toURL(), apiOut.toUri().toURL() }, EvolutionLab.class.getClassLoader())) {
                        api.Api impl = (api.Api) cl.loadClass("impl.Service").getDeclaredConstructor().newInstance();
                        sc.run().accept(impl); pass++;
                    } catch (Throwable t) { failed.add(sc.name()); }
                }
                long total_lines = 0, ruleSites = 0;
                for (Path p : files(src).values()) for (String l : Files.readAllLines(p)) {
                    if (!l.isBlank()) total_lines++;
                    if (l.matches(".*(>=|<) ?capacity.*")) ruleSites++;
                }
                String change = "";
                if (v > 0) {
                    Map<String, Path> before = files(Path.of("code", style, "v" + (v - 1))), after = files(src);
                    int touched = 0, add = 0, del = 0;
                    var names = new java.util.TreeSet<>(before.keySet()); names.addAll(after.keySet());
                    for (String n : names) { int[] dd = diff(lines(before.getOrDefault(n, Path.of("/none"))), lines(after.getOrDefault(n, Path.of("/none")))); if (dd[0] + dd[1] > 0) { touched++; add += dd[0]; del += dd[1]; } }
                    change = "；相对上一版改动 " + touched + " 个文件，+" + add + " −" + del + " 行";
                }
                out(style + ".v" + v, "非空行 " + total_lines + "，名额条件写了 " + ruleSites + " 处" + change + "；验收场景 " + pass + "/" + total + " 通过" + (failed.isEmpty() ? "" : " 失败 " + failed));
            }
        }
    }
}
