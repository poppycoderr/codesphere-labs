import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组合根的生命周期：按依赖顺序启动，启动前检查缺失依赖与环，部分启动失败时只按相反顺序关闭已启动的组件，
 * 关闭时某个组件失败不影响其余组件关闭并汇总错误；「已注册」与「已就绪」分开。
 */
public class Lab {

    static final List<String> journal = new ArrayList<>();

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static final class Component {
        final String name;
        final List<String> deps;
        final boolean failOnStart;
        final boolean failOnStop;
        boolean running;

        Component(String name, List<String> deps, boolean failOnStart, boolean failOnStop) {
            this.name = name;
            this.deps = deps;
            this.failOnStart = failOnStart;
            this.failOnStop = failOnStop;
        }

        void start() {
            if (failOnStart) {
                throw new IllegalStateException(name + " 启动失败：端口被占用");
            }
            running = true;
            journal.add("启动 " + name);
        }

        void stop() {
            journal.add("关闭 " + name);
            running = false;
            if (failOnStop) {
                throw new IllegalStateException(name + " 关闭失败：刷盘超时");
            }
        }
    }

    static Map<String, Component> system(String failStart, String failStop) {
        Map<String, Component> m = new LinkedHashMap<>();
        m.put("http", new Component("http", List.of("orders", "cache"), "http".equals(failStart), "http".equals(failStop)));
        m.put("orders", new Component("orders", List.of("db", "cache"), "orders".equals(failStart), "orders".equals(failStop)));
        m.put("cache", new Component("cache", List.of("config"), "cache".equals(failStart), "cache".equals(failStop)));
        m.put("db", new Component("db", List.of("config"), "db".equals(failStart), "db".equals(failStop)));
        m.put("config", new Component("config", List.of(), "config".equals(failStart), "config".equals(failStop)));
        return m;
    }

    /** 拓扑排序；缺失依赖与环在启动任何组件之前报错。 */
    static List<String> order(Map<String, Component> components) {
        for (Component c : components.values()) {
            for (String d : c.deps) {
                if (!components.containsKey(d)) {
                    throw new IllegalStateException("缺少依赖：" + c.name + " → " + d);
                }
            }
        }
        List<String> sorted = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (String name : components.keySet()) {
            visit(name, components, done, new ArrayDeque<>(), sorted);
        }
        return sorted;
    }

    static void visit(String name, Map<String, Component> cs, Set<String> done, Deque<String> path, List<String> sorted) {
        if (done.contains(name)) {
            return;
        }
        if (path.contains(name)) {
            List<String> cycle = new ArrayList<>(path);
            cycle = cycle.subList(cycle.indexOf(name), cycle.size());
            throw new IllegalStateException("依赖成环：" + String.join(" → ", cycle) + " → " + name);
        }
        path.addLast(name);
        for (String d : cs.get(name).deps) {
            visit(d, cs, done, path, sorted);
        }
        path.removeLast();
        done.add(name);
        sorted.add(name);
    }

    /** 朴素启动：遇到失败直接抛出，已启动的组件不管。 */
    static void naiveStart(Map<String, Component> cs) {
        for (String n : order(cs)) {
            cs.get(n).start();
        }
    }

    /** 启动失败时按相反顺序关闭已启动的组件，原始异常带上关闭时的异常。 */
    static List<Component> startAll(Map<String, Component> cs) {
        List<Component> started = new ArrayList<>();
        try {
            for (String n : order(cs)) {
                Component c = cs.get(n);
                c.start();
                started.add(c);
            }
            return started;
        } catch (RuntimeException e) {
            RuntimeException stopError = stopAll(started);
            if (stopError != null) {
                e.addSuppressed(stopError);
            }
            throw e;
        }
    }

    /** 按相反顺序关闭；某个组件关闭失败也继续关闭其余组件，最后汇总。 */
    static RuntimeException stopAll(List<Component> started) {
        RuntimeException first = null;
        for (int i = started.size() - 1; i >= 0; i--) {
            try {
                started.get(i).stop();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = new IllegalStateException("关闭过程中有组件失败");
                }
                first.addSuppressed(e);
            }
        }
        return first;
    }

    static String running(Map<String, Component> cs) {
        List<String> r = cs.values().stream().filter(c -> c.running).map(c -> c.name).toList();
        return r.isEmpty() ? "无" : String.join("、", r);
    }

    public static void main(String[] args) {
        Map<String, Component> ok = system(null, null);
        out("order", "启动顺序：" + String.join(" → ", order(ok)));

        Map<String, Component> missing = system(null, null);
        missing.remove("db");
        try {
            startAll(missing);
        } catch (IllegalStateException e) {
            out("check.missing", "删掉 db 后启动：" + e.getMessage() + "；已启动的组件 " + running(missing));
        }
        Map<String, Component> cyclic = system(null, null);
        cyclic.put("config", new Component("config", List.of("http"), false, false));
        try {
            startAll(cyclic);
        } catch (IllegalStateException e) {
            out("check.cycle", "config 反过来依赖 http：" + e.getMessage() + "；已启动的组件 " + running(cyclic));
        }

        journal.clear();
        Map<String, Component> naive = system("orders", null);
        try {
            naiveStart(naive);
        } catch (IllegalStateException e) {
            out("start.naive", "orders 启动失败，朴素启动直接抛出：" + e.getMessage() + "；仍在运行 " + running(naive) + "（进程退出前没人关闭它们）");
        }

        journal.clear();
        Map<String, Component> rollback = system("orders", null);
        try {
            startAll(rollback);
        } catch (IllegalStateException e) {
            out("start.rollback", "同样失败，按相反顺序关闭已启动的组件：" + String.join("，", journal) + "；仍在运行 " + running(rollback)
                    + "；抛出的是原始异常「" + e.getMessage() + "」");
        }

        journal.clear();
        Map<String, Component> stopNaive = system(null, "cache");
        List<Component> started = startAll(stopNaive);
        journal.clear();
        try {
            for (int i = started.size() - 1; i >= 0; i--) {
                started.get(i).stop();
            }
        } catch (IllegalStateException e) {
            out("stop.naive", "关闭时 cache 抛错，朴素循环在第一个异常处停下：" + String.join("，", journal) + "；仍在运行 " + running(stopNaive));
        }

        journal.clear();
        Map<String, Component> stopAll = system(null, "cache");
        List<Component> started2 = startAll(stopAll);
        journal.clear();
        RuntimeException err = stopAll(started2);
        out("stop.all", "继续关闭其余组件并汇总：" + String.join("，", journal) + "；仍在运行 " + running(stopAll)
                + "；汇总异常里有 " + err.getSuppressed().length + " 个失败（" + err.getSuppressed()[0].getMessage() + "）");

        // 已注册不等于已就绪
        boolean endpointsRegistered = true;
        boolean cacheWarmed = false;
        out("ready", "http 的路由都已注册=" + endpointsRegistered + "，但缓存预热还没完成：按「已注册」判断就绪会接流量，按「依赖都就绪」判断返回 "
                + (endpointsRegistered && cacheWarmed ? "就绪" : "未就绪"));
    }
}
