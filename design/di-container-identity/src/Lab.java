import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/** 依赖注入容器的绑定身份与依赖图：用最小容器比较「只按 Class」与「类型 + 限定符」两种身份在注册、图检查和解析中的差别 */
public class Lab {

    // ---------- 被装配的组件 ----------
    interface Source { String read(); }
    static class RawSource implements Source { public String read() { return "raw"; } }
    /** 同一类型的装饰：每日汇总读取原始数据源，两者都是 Source，只靠限定符区分 */
    static class DailySource implements Source {
        final Source raw;
        @Inject DailySource(@Named("raw") Source raw) { this.raw = raw; }
        public String read() { return "daily(" + raw.read() + ")"; }
    }
    /** 真正的环：甲依赖乙，乙又依赖甲 */
    static class LoopA implements Source { @Inject LoopA(@Named("b") Source b) {} public String read() { return "a"; } }
    static class LoopB implements Source { @Inject LoopB(@Named("a") Source a) {} public String read() { return "b"; } }
    /** 菱形：Top 依赖 Left、Right，二者都依赖 Base */
    static class Base {}
    static class Left { @Inject Left(Base b) {} }
    static class Right { @Inject Right(Base b) {} }
    static class Top { @Inject Top(Left l, Right r) {} }
    /** 延迟边：Clock 只拿 Provider<Ticker>，Ticker 直接依赖 Clock */
    static class Ticker { @Inject Ticker(Clock c) {} }
    static class Clock { final Provider<Ticker> ticker; @Inject Clock(Provider<Ticker> t) { ticker = t; } }
    /** 同样的结构，但在构造器里立刻调用 get() */
    static class EagerTicker { @Inject EagerTicker(EagerClock c) {} }
    static class EagerClock { @Inject EagerClock(Provider<EagerTicker> t) { t.get(); } }
    static class NeedsMissing { @Inject NeedsMissing(@Named("absent") Source s) {} }

    // ---------- 绑定身份 ----------
    /** 绑定键：类型加可选限定符；注解实例按成员值比较相等，所以两处写的 @Named("raw") 是同一个键 */
    record Key(Class<?> type, Annotation qualifier) {
        static Key of(Class<?> t) { return new Key(t, null); }
        public String toString() { return type.getSimpleName() + (qualifier == null ? "" : "@" + ((Named) qualifier).value()); }
    }
    /** 依赖边：目标键，以及是否经过 Provider 延迟 */
    record Dep(Key key, boolean lazy) {}

    static Annotation qualifierOf(Annotation[] annotations) {
        for (Annotation a : annotations) if (a.annotationType().isAnnotationPresent(Qualifier.class)) return a;
        return null;
    }

    /** 按 JSR-330：有 @Inject 的构造器就用它，否则用无参构造器 */
    static Constructor<?> injectConstructor(Class<?> impl) {
        return Arrays.stream(impl.getDeclaredConstructors()).filter(x -> x.isAnnotationPresent(Inject.class)).findFirst()
                .or(() -> Arrays.stream(impl.getDeclaredConstructors()).filter(x -> x.getParameterCount() == 0).findFirst())
                .orElseThrow(() -> new IllegalStateException(impl.getSimpleName() + " 既没有 @Inject 构造器也没有无参构造器"));
    }

    static List<Dep> depsOf(Class<?> impl) {
        Constructor<?> c = injectConstructor(impl);
        List<Dep> deps = new ArrayList<>();
        Type[] types = c.getGenericParameterTypes();
        for (int i = 0; i < types.length; i++) {
            Annotation q = qualifierOf(c.getParameterAnnotations()[i]);
            if (types[i] instanceof ParameterizedType p && p.getRawType() == Provider.class)
                deps.add(new Dep(new Key((Class<?>) p.getActualTypeArguments()[0], q), true));
            else deps.add(new Dep(new Key((Class<?>) types[i], q), false));
        }
        return deps;
    }

    /** 最小容器：bindings 的键由调用方决定用 Key 还是只用 Class，以便对比 */
    static class Container {
        final boolean classOnly;
        /** 绑定表已经按完整键存放，但检查路径仍只记 Class：注册和图检查用了两种身份 */
        boolean pathByClass;
        final Map<Object, Class<?>> bindings = new LinkedHashMap<>();
        Container(boolean classOnly) { this.classOnly = classOnly; }
        Object id(Key k) { return classOnly ? k.type() : k; }
        void bind(Key key, Class<?> impl) { bindings.put(id(key), impl); }

        /** 配置期检查：沿立即依赖做深度优先，path 是当前递归路径；延迟边只检查目标是否注册，不递归 */
        void check(Key root) { visit(root, new ArrayList<>()); }
        private void visit(Key key, List<Object> path) {
            Object id = id(key), step = pathByClass ? key.type() : id;
            if (path.contains(step)) throw new IllegalStateException("循环依赖：" + chain(path, step));
            Class<?> impl = bindings.get(id);
            if (impl == null) throw new IllegalStateException("缺少依赖：" + chain(path, id));
            path.add(step);
            for (Dep d : depsOf(impl)) {
                if (d.lazy()) { if (!bindings.containsKey(id(d.key()))) throw new IllegalStateException("缺少延迟依赖：" + chain(path, id(d.key()))); }
                else visit(d.key(), path);
            }
            path.remove(path.size() - 1);
        }
        /** 运行期解析：constructing 记录正在构造的键，同一键重入说明构造过程中真的绕回来了 */
        final Set<Object> constructing = new HashSet<>();
        Object get(Key key) {
            Object id = id(key);
            if (!constructing.add(id)) throw new IllegalStateException("构造过程中重入：" + name(id));
            try {
                Class<?> impl = bindings.get(id);
                Constructor<?> c = injectConstructor(impl);
                List<Dep> deps = depsOf(impl);
                Object[] args = new Object[deps.size()];
                for (int i = 0; i < args.length; i++) {
                    Dep d = deps.get(i);
                    args[i] = d.lazy() ? (Provider<Object>) () -> get(d.key()) : get(d.key());
                }
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
            } finally { constructing.remove(id); }
        }
        static String name(Object id) { return id instanceof Class<?> c ? c.getSimpleName() : id.toString(); }
        static String chain(List<Object> path, Object last) {
            List<String> names = new ArrayList<>(); for (Object o : path) names.add(name(o)); names.add(name(last));
            return String.join(" → ", names);
        }
    }

    /** 判断「有没有环」时只用已访问集合的错误做法：菱形里第二次碰到 Base 就会被当成环 */
    static String visitedOnly(Container c, Key root) {
        Set<Object> seen = new HashSet<>(); Deque<Key> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Key k = todo.pop();
            if (!seen.add(c.id(k))) return "报告循环依赖（在 " + k + " 处）";
            for (Dep d : depsOf(c.bindings.get(c.id(k)))) if (!d.lazy()) todo.push(d.key());
        }
        return "无环";
    }

    // ---------- 泛型引用：匿名子类捕获类型参数 ----------
    abstract static class TypeRef<T> {
        final Type type = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        /** 按 getClass() 比较：每个匿名子类都是不同的类 */
        boolean equalsByClass(Object o) { return o != null && getClass() == o.getClass(); }
        /** 按捕获到的 Type 比较 */
        boolean equalsByType(Object o) { return o instanceof TypeRef<?> r && type.equals(r.type); }
    }

    static Named named(String v) { return new Named() { public String value() { return v; } public Class<? extends Annotation> annotationType() { return Named.class; }
        public boolean equals(Object o) { return o instanceof Named n && n.value().equals(v); } public int hashCode() { return (127 * "value".hashCode()) ^ v.hashCode(); } }; }

    static String attempt(Runnable r) { try { r.run(); return "通过"; } catch (RuntimeException e) { return e.getMessage(); } }
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    public static void main(String[] args) {
        Key raw = new Key(Source.class, named("raw")), daily = new Key(Source.class, named("daily"));
        for (boolean classOnly : new boolean[]{true, false}) {
            String tag = classOnly ? "class" : "key";
            Container c = new Container(classOnly);
            c.bind(raw, RawSource.class); c.bind(daily, DailySource.class);
            out(tag + ".bindings", "注册 Source@raw 与 Source@daily 后，容器里有 " + c.bindings.size() + " 个绑定：" + c.bindings.values().stream().map(Class::getSimpleName).toList());
            out(tag + ".check", "检查 Source@daily：" + attempt(() -> c.check(daily)));
            String[] value = {""};
            String r = attempt(() -> value[0] = ((Source) c.get(raw)).read());
            out(tag + ".resolve", "解析 Source@raw：" + (r.equals("通过") ? "得到 " + value[0] : r));
        }
        Container mixed = new Container(false);
        mixed.bind(raw, RawSource.class); mixed.bind(daily, DailySource.class); mixed.pathByClass = true;
        out("mixed.check", "绑定按完整键、检查路径只记 Class，检查 Source@daily：" + attempt(() -> mixed.check(daily)));
        Container loop = new Container(false);
        loop.bind(new Key(Source.class, named("a")), LoopA.class); loop.bind(new Key(Source.class, named("b")), LoopB.class);
        out("key.real_cycle", "Source@a 依赖 Source@b、Source@b 又依赖 Source@a：" + attempt(() -> loop.check(new Key(Source.class, named("a")))));

        Container diamond = new Container(false);
        for (Class<?> t : List.of(Base.class, Left.class, Right.class, Top.class)) diamond.bind(Key.of(t), t);
        out("diamond.path", "菱形依赖，按当前路径判断：" + attempt(() -> diamond.check(Key.of(Top.class))));
        out("diamond.visited", "菱形依赖，只看是否访问过：" + visitedOnly(diamond, Key.of(Top.class)));

        Container lazy = new Container(false);
        lazy.bind(Key.of(Clock.class), Clock.class); lazy.bind(Key.of(Ticker.class), Ticker.class);
        out("lazy.check", "Clock 只持有 Provider<Ticker>，Ticker 依赖 Clock，配置期检查：" + attempt(() -> lazy.check(Key.of(Clock.class))));
        out("lazy.get", "解析 Clock：" + attempt(() -> lazy.get(Key.of(Clock.class))) + "；之后调用 clock.ticker.get()：" + attempt(() -> ((Clock) lazy.get(Key.of(Clock.class))).ticker.get()));
        Container eager = new Container(false);
        eager.bind(Key.of(EagerClock.class), EagerClock.class); eager.bind(Key.of(EagerTicker.class), EagerTicker.class);
        out("eager.check", "同样结构但构造器里立刻 get()，配置期检查：" + attempt(() -> eager.check(Key.of(EagerClock.class))));
        out("eager.get", "解析 EagerClock：" + attempt(() -> eager.get(Key.of(EagerClock.class))));

        Container missing = new Container(false);
        missing.bind(Key.of(NeedsMissing.class), NeedsMissing.class);
        out("missing", attempt(() -> missing.check(Key.of(NeedsMissing.class))));

        TypeRef<List<String>> r1 = new TypeRef<>() {}, r2 = new TypeRef<>() {};
        out("ref.class_equals", "两处写的 new TypeRef<List<String>>(){}：按 getClass() 比较相等=" + r1.equalsByClass(r2) + "，按捕获的 Type 比较相等=" + r1.equalsByType(r2));
        out("named.equals", "两个独立创建的 @Named(\"raw\") 注解实例 equals=" + named("raw").equals(qualifierOf(DailySource.class.getDeclaredConstructors()[0].getParameterAnnotations()[0])));
    }
}
