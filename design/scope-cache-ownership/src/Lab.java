import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 单例作用域的缓存所有权：作用域装饰器放在哪里、谁持有缓存，决定了「单例」在什么范围内唯一 */
public class Lab {

    /** 创建职责：怎样造一个对象，并声明依赖哪些键 */
    interface ComponentProvider<T> {
        T create(Context ctx);
        default List<String> deps() { return List.of(); }
    }
    interface Context { Object get(String key); }

    /** 不加同步的单例装饰器：第一次创建后缓存实例，null 表示「还没创建」 */
    static class NaiveSingleton<T> implements ComponentProvider<T> {
        final ComponentProvider<T> inner; T instance;
        NaiveSingleton(ComponentProvider<T> inner) { this.inner = inner; }
        public T create(Context ctx) { if (instance == null) instance = inner.create(ctx); return instance; }
        public List<String> deps() { return inner.deps(); }
    }
    /** 忘记转发依赖元数据的装饰器：图检查看不到被包装组件的依赖 */
    static class OpaqueSingleton<T> extends NaiveSingleton<T> {
        OpaqueSingleton(ComponentProvider<T> inner) { super(inner); }
        public List<String> deps() { return List.of(); }
    }
    /** 加锁、并用独立标志区分「已创建」与「创建结果为 null」 */
    static class SafeSingleton<T> implements ComponentProvider<T> {
        final ComponentProvider<T> inner; T instance; boolean created;
        SafeSingleton(ComponentProvider<T> inner) { this.inner = inner; }
        public synchronized T create(Context ctx) { if (!created) { instance = inner.create(ctx); created = true; } return instance; }
        public List<String> deps() { return inner.deps(); }
    }

    /** 配置：键到 Provider 的表；build 生成容器，copyProviders 决定是否共享 Provider 对象 */
    static class Config {
        final Map<String, ComponentProvider<?>> providers = new LinkedHashMap<>();
        final Map<String, Supplier<ComponentProvider<?>>> recipes = new LinkedHashMap<>();
        void bind(String key, Supplier<ComponentProvider<?>> recipe) { recipes.put(key, recipe); providers.put(key, recipe.get()); }
        /** 只复制表：多个容器拿到的是同一批 Provider 对象，单例缓存随之共享 */
        Context buildShallow() { Map<String, ComponentProvider<?>> m = new HashMap<>(providers); return k -> m.get(k).create(ctxOf(m)); }
        /** 每个容器按配方重新创建 Provider，缓存归容器所有 */
        Context buildOwned() { Map<String, ComponentProvider<?>> m = new HashMap<>(); recipes.forEach((k, r) -> m.put(k, r.get())); return k -> m.get(k).create(ctxOf(m)); }
        static Context ctxOf(Map<String, ComponentProvider<?>> m) { return k -> m.get(k).create(ctxOf(m)); }
    }

    static class Clock { static final AtomicInteger built = new AtomicInteger(); final int id = built.incrementAndGet(); }

    /** 配置期检查：沿 deps() 找环，装饰器不转发就看不到 */
    static String check(Map<String, ComponentProvider<?>> m, String root) {
        try { visit(m, root, new ArrayList<>()); return "通过"; } catch (IllegalStateException e) { return e.getMessage(); }
    }
    static void visit(Map<String, ComponentProvider<?>> m, String key, List<String> path) {
        if (path.contains(key)) throw new IllegalStateException("循环依赖：" + String.join(" → ", path) + " → " + key);
        path.add(key); for (String d : m.get(key).deps()) visit(m, d, path); path.remove(path.size() - 1);
    }

    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    /** 两个线程同时第一次取值；inner 在创建时等闸门，保证两次检查 instance==null 都发生在赋值之前 */
    static int raceCount(boolean safe) throws Exception {
        AtomicInteger created = new AtomicInteger(); CountDownLatch bothInside = new CountDownLatch(2);
        ComponentProvider<Object> inner = ctx -> { created.incrementAndGet(); bothInside.countDown(); try { bothInside.await(200, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } return new Object(); };
        ComponentProvider<Object> p = safe ? new SafeSingleton<>(inner) : new NaiveSingleton<>(inner);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        Future<Object> a = ex.submit(() -> p.create(null)), b = ex.submit(() -> p.create(null));
        Object x = a.get(), y = b.get(); ex.shutdown();
        return created.get() * 10 + (x == y ? 1 : 2);
    }

    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        cfg.bind("clock", () -> new NaiveSingleton<>(ctx -> new Clock()));
        Context s1 = cfg.buildShallow(), s2 = cfg.buildShallow();
        out("shallow", "同一份配置浅复制出两个容器：容器一的 clock 是 #" + ((Clock) s1.get("clock")).id + "，容器二的 clock 是 #" + ((Clock) s2.get("clock")).id + "（两个容器共享一个缓存）");
        Context o1 = cfg.buildOwned(), o2 = cfg.buildOwned();
        out("owned", "每个容器按配方重建 Provider：容器一 #" + ((Clock) o1.get("clock")).id + "、再取一次 #" + ((Clock) o1.get("clock")).id + "，容器二 #" + ((Clock) o2.get("clock")).id);

        Map<String, ComponentProvider<?>> alias = new HashMap<>();
        NaiveSingleton<Clock> shared = new NaiveSingleton<>(ctx -> new Clock());
        alias.put("clock@utc", shared); alias.put("clock@default", shared);
        alias.put("clock@local", new NaiveSingleton<>(ctx -> new Clock()));
        Context ac = Config.ctxOf(alias);
        out("alias", "两个限定符绑定同一个装饰器：@utc #" + ((Clock) ac.get("clock@utc")).id + "、@default #" + ((Clock) ac.get("clock@default")).id + "；另一个装饰器 @local #" + ((Clock) ac.get("clock@local")).id);

        int naive = raceCount(false), safe = raceCount(true);
        out("race.naive", "两个线程同时第一次取值，不加锁：创建了 " + naive / 10 + " 次，两个线程拿到" + (naive % 10 == 1 ? "同一个" : "不同的") + "对象");
        out("race.safe", "加锁：创建了 " + safe / 10 + " 次，两个线程拿到" + (safe % 10 == 1 ? "同一个" : "不同的") + "对象");

        AtomicInteger nullCalls = new AtomicInteger();
        NaiveSingleton<Object> nn = new NaiveSingleton<>(ctx -> { nullCalls.incrementAndGet(); return null; });
        for (int i = 0; i < 3; i++) nn.create(null);
        AtomicInteger nullCalls2 = new AtomicInteger();
        SafeSingleton<Object> sn = new SafeSingleton<>(ctx -> { nullCalls2.incrementAndGet(); return null; });
        for (int i = 0; i < 3; i++) sn.create(null);
        out("null", "工厂合法地返回 null，取 3 次：用 instance==null 判断时工厂被调用 " + nullCalls.get() + " 次；用独立的 created 标志时 " + nullCalls2.get() + " 次");

        AtomicInteger attempts = new AtomicInteger();
        NaiveSingleton<String> flaky = new NaiveSingleton<>(ctx -> { if (attempts.incrementAndGet() == 1) throw new IllegalStateException("配置未就绪"); return "ready"; });
        String first; try { flaky.create(null); first = "成功"; } catch (IllegalStateException e) { first = "失败：" + e.getMessage(); }
        out("failure", "第一次创建抛异常（" + first + "），第二次取值得到 " + flaky.create(null) + "，工厂共被调用 " + attempts.get() + " 次（失败没有被缓存）");

        Map<String, ComponentProvider<?>> loop = new HashMap<>();
        ComponentProvider<Object> a = new ComponentProvider<>() { public Object create(Context c) { return c.get("b"); } public List<String> deps() { return List.of("b"); } };
        ComponentProvider<Object> b = new ComponentProvider<>() { public Object create(Context c) { return c.get("a"); } public List<String> deps() { return List.of("a"); } };
        loop.put("a", new OpaqueSingleton<>(a)); loop.put("b", b);
        out("deps.opaque", "装饰器不转发依赖元数据，检查 a ↔ b 的环：" + check(loop, "a"));
        loop.put("a", new NaiveSingleton<>(a));
        out("deps.forward", "装饰器转发依赖元数据：" + check(loop, "a"));
    }
}
