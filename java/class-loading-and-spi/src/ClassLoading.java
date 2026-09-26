import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.*;

/**
 * 类加载的六组观察，输出为「键<TAB>事实」。运行时把几组源码编译进不同目录，再用不同的类加载器加载：
 * 1. 同名类、不同加载器；2. 初始化时机与准备阶段的零值；3. ServiceLoader 与线程上下文类加载器；
 * 4. 父优先与子优先；5. 类加载器能否被回收；6. 编译期与运行期版本不一致。
 */
public class ClassLoading {
    static Path root;

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : "build/tmp").toAbsolutePath();
        deleteTree(root);
        sameNameDifferentLoader();
        initialization();
        serviceLoader();
        delegationOrder();
        unloading();
        versionMismatch();
    }

    // ---------- 1 ----------
    static void sameNameDifferentLoader() throws Exception {
        Path dir = compile("plugin", Map.of(
                "com/example/Plugin.java", "package com.example; public class Plugin { public String name() { return \"plugin\"; } }",
                "com/example/Host.java", "package com.example; public class Host { public static String accept(Object o) { return ((Plugin) o).name(); } }"));
        try (URLClassLoader a = loader(dir, null, "plugin-a"); URLClassLoader b = loader(dir, null, "plugin-b")) {
            Class<?> ca = a.loadClass("com.example.Plugin"), cb = b.loadClass("com.example.Plugin");
            Object ob = cb.getDeclaredConstructor().newInstance();
            String cast;
            try { a.loadClass("com.example.Host").getMethod("accept", Object.class).invoke(null, ob); cast = "成功"; }
            catch (java.lang.reflect.InvocationTargetException e) { cast = e.getCause().getMessage(); }
            System.out.printf("identity\t两个加载器加载同一份 com.example.Plugin：名称相同=%s，Class 对象相同=%s%n", ca.getName().equals(cb.getName()), ca == cb);
            System.out.printf("identity.cast\t%s%n", cast.replaceAll("@[0-9a-f]+", "@<hash>"));
        }
    }

    // ---------- 2 ----------
    static void initialization() throws Exception {
        Path dir = compile("init", Map.of("demo/Config.java", """
                package demo;
                public class Config {
                    public static final int CONSTANT = 7;
                    public static final Integer BOXED = 8;
                    static int early = readLate();
                    static int late = 10;
                    static { System.out.println("init\\tConfig 的静态初始化执行了；early=" + early + "，late=" + late); }
                    static int readLate() { return late; }
                }
                """, "demo/Probe.java", """
                package demo;
                public class Probe {
                    public static void run() throws Exception {
                        System.out.println("init.step\\t读取编译期常量 Config.CONSTANT=" + Config.CONSTANT);
                        Config[] array = new Config[3];
                        System.out.println("init.step\\t创建 Config[3] 数组，长度 " + array.length);
                        Class<?> c = Class.forName("demo.Config", false, Probe.class.getClassLoader());
                        System.out.println("init.step\\tClass.forName(name, false, loader) 得到 " + c.getSimpleName());
                        System.out.println("init.step\\t读取 Config.BOXED=" + Config.BOXED);
                    }
                }
                """));
        try (URLClassLoader l = loader(dir, ClassLoading.class.getClassLoader(), "init")) {
            l.loadClass("demo.Probe").getMethod("run").invoke(null);
        }
    }

    // ---------- 3 ----------
    static void serviceLoader() throws Exception {
        Path api = compile("spi-api", Map.of("api/Greeter.java", """
                package api;
                import java.util.*;
                public interface Greeter {
                    String greet();
                    /** 平台层的代码：用线程上下文类加载器查找实现。 */
                    static int countViaContext() { int n = 0; for (Greeter g : ServiceLoader.load(Greeter.class)) n++; return n; }
                    static int countViaOwnLoader() { int n = 0; for (Greeter g : ServiceLoader.load(Greeter.class, Greeter.class.getClassLoader())) n++; return n; }
                }
                """));
        Path impl = compile("spi-impl", Map.of("impl/HelloGreeter.java", "package impl; public class HelloGreeter implements api.Greeter { public String greet() { return \"hello\"; } }"), api);
        Files.createDirectories(impl.resolve("META-INF/services"));
        Files.writeString(impl.resolve("META-INF/services/api.Greeter"), "impl.HelloGreeter\n");
        try (URLClassLoader platform = loader(api, null, "platform"); URLClassLoader app = loader(impl, platform, "app")) {
            Class<?> greeter = platform.loadClass("api.Greeter");
            ClassLoader saved = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(platform);
                int ctxPlatform = (int) greeter.getMethod("countViaContext").invoke(null);
                Thread.currentThread().setContextClassLoader(app);
                int ctxApp = (int) greeter.getMethod("countViaContext").invoke(null);
                int own = (int) greeter.getMethod("countViaOwnLoader").invoke(null);
                System.out.printf("spi\t接口在父加载器、实现在子加载器：上下文类加载器为父加载器时找到 %d 个，为子加载器时找到 %d 个；用接口自己的加载器查找找到 %d 个%n", ctxPlatform, ctxApp, own);

                // 线程池的工作线程在创建时继承当时的上下文类加载器，之后一直持有
                ExecutorService pool = Executors.newSingleThreadExecutor();
                pool.submit(() -> {}).get();
                Thread.currentThread().setContextClassLoader(saved);
                String seen = pool.submit(() -> Thread.currentThread().getContextClassLoader().getName()).get();
                System.out.printf("spi.pool\t在上下文为 app 时创建的线程池，调用方切回原加载器后，工作线程的上下文类加载器仍是 %s%n", seen);
                pool.shutdown();
            } finally {
                Thread.currentThread().setContextClassLoader(saved);
            }
        }
    }

    // ---------- 4 ----------
    static void delegationOrder() throws Exception {
        Path v1 = compile("lib-v1", Map.of("lib/Version.java", "package lib; public class Version { public static String value() { return \"1.0\"; } }"));
        Path v2 = compile("lib-v2", Map.of("lib/Version.java", "package lib; public class Version { public static String value() { return \"2.0\"; } }"));
        try (URLClassLoader shared = loader(v1, null, "shared");
             URLClassLoader parentFirst = loader(v2, shared, "parent-first");
             URLClassLoader childFirst = new ChildFirstLoader(v2, shared)) {
            String pf = (String) parentFirst.loadClass("lib.Version").getMethod("value").invoke(null);
            String cf = (String) childFirst.loadClass("lib.Version").getMethod("value").invoke(null);
            System.out.printf("delegation\t父加载器有 lib.Version 1.0、插件目录有 2.0：父优先得到 %s，子优先得到 %s%n", pf, cf);
        }
        Path fake = compile("fake-core", Map.of("java/lang/Hacked.java", "package java.lang; public class Hacked {}"), null, List.of("--patch-module", "java.base=" + root.resolve("fake-core-src")));
        try (URLClassLoader l = new ChildFirstLoader(fake, null)) {
            String r;
            try { l.loadClass("java.lang.Hacked"); r = "成功"; } catch (Throwable e) { r = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            System.out.printf("delegation.core\t子优先加载器自己定义 java.lang.Hacked：%s%n", r);
        }
    }

    static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(Path dir, ClassLoader parent) throws MalformedURLException { super("child-first", new URL[] {dir.toUri().toURL()}, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null && !name.startsWith("java.")) {
                    try { c = findClass(name); } catch (ClassNotFoundException ignored) { }
                }
                if (c == null && name.startsWith("java.") && findResource(name.replace('.', '/') + ".class") != null) {
                    byte[] bytes;
                    try (InputStream in = findResource(name.replace('.', '/') + ".class").openStream()) { bytes = in.readAllBytes(); } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                    c = defineClass(name, bytes, 0, bytes.length);
                }
                if (c == null) c = super.loadClass(name, false);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    // ---------- 5 ----------
    static final List<Object> REGISTRY = new ArrayList<>(); // 父加载器里的静态注册表

    static void unloading() throws Exception {
        Path dir = compile("leak", Map.of("leak/Handler.java", "package leak; public class Handler { byte[] payload = new byte[1 << 20]; }"));
        System.out.printf("unload.clean\t丢弃加载器与实例后：%s%n", collected(dir, obj -> { }));
        System.out.printf("unload.static\t实例登记在父加载器的静态列表里：%s%n", collected(dir, REGISTRY::add));
        REGISTRY.clear();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ThreadLocal<Object> tl = new ThreadLocal<>();
        System.out.printf("unload.threadlocal\t实例放进长期存活线程的 ThreadLocal：%s%n", collected(dir, obj -> {
            try { pool.submit(() -> tl.set(obj)).get(); } catch (Exception e) { throw new RuntimeException(e); }
        }));
        pool.submit(tl::remove).get();
        System.out.printf("unload.threadlocal.removed\t在同一线程里 remove 之后：%s%n", collectedAfter());
        pool.shutdown();
    }

    static WeakReference<ClassLoader> lastLoader;

    interface Keep { void accept(Object o) throws Exception; }

    static String collected(Path dir, Keep keep) throws Exception {
        URLClassLoader l = loader(dir, null, "leak");
        Object handler = l.loadClass("leak.Handler").getDeclaredConstructor().newInstance();
        keep.accept(handler);
        lastLoader = new WeakReference<>(l);
        l.close();
        l = null; handler = null;
        return collectedAfter();
    }

    static String collectedAfter() throws InterruptedException {
        for (int i = 0; i < 20 && lastLoader.get() != null; i++) { System.gc(); Thread.sleep(50); }
        return lastLoader.get() == null ? "加载器已被回收" : "加载器仍然存活";
    }

    // ---------- 6 ----------
    static void versionMismatch() throws Exception {
        Path v2 = compile("api-v2", Map.of("api/Client.java", "package api; public class Client { public String call() { return \"v1\"; } public String call(int timeoutMs) { return \"v2\"; } }"));
        Path v1 = compile("api-v1", Map.of("api/Client.java", "package api; public class Client { public String call() { return \"v1\"; } }"));
        Path app = compile("caller", Map.of("app/Caller.java", "package app; public class Caller { public static String run() { return new api.Client().call(500); } }"), v2);
        try (URLClassLoader l = new URLClassLoader("run", new URL[] {v1.toUri().toURL(), app.toUri().toURL()}, null)) {
            String r;
            try { r = (String) l.loadClass("app.Caller").getMethod("run").invoke(null); }
            catch (java.lang.reflect.InvocationTargetException e) { r = e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage(); }
            System.out.printf("mismatch\t按 api 2.0 编译、运行时 classpath 上是 1.0：%s%n", r);
        }
    }

    // ---------- 工具 ----------
    static Path compile(String name, Map<String, String> sources) throws IOException { return compile(name, sources, null, List.of()); }

    static Path compile(String name, Map<String, String> sources, Path classpath) throws IOException { return compile(name, sources, classpath, List.of()); }

    static Path compile(String name, Map<String, String> sources, Path classpath, List<String> extra) throws IOException {
        Path src = root.resolve(name + "-src"), out = root.resolve(name);
        Files.createDirectories(out);
        List<File> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path p = src.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            files.add(p.toFile());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            List<String> opts = new ArrayList<>(List.of("-d", out.toString()));
            if (classpath != null) opts.addAll(List.of("-cp", classpath.toString()));
            opts.addAll(extra);
            StringWriter err = new StringWriter();
            if (!javac.getTask(err, fm, null, opts, null, fm.getJavaFileObjectsFromFiles(files)).call()) throw new IllegalStateException(err.toString());
        }
        return out;
    }

    static URLClassLoader loader(Path dir, ClassLoader parent, String name) throws MalformedURLException {
        return new URLClassLoader(name, new URL[] {dir.toUri().toURL()}, parent);
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
    }
}
