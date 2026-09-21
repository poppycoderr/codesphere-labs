import java.lang.reflect.*;

public class Frames {
    interface DataSource { String read(String key); }
    static class Db implements DataSource { public String read(String k) { throw new IllegalStateException("连接超时"); } }
    record Logging(DataSource d) implements DataSource { public String read(String k) { return d.read(k); } }
    record Caching(DataSource d) implements DataSource { public String read(String k) { return d.read(k); } }
    record Metrics(DataSource d) implements DataSource { public String read(String k) { return d.read(k); } }

    static int frames(DataSource ds) {
        try { ds.read("k"); return -1; } catch (RuntimeException e) {
            Throwable t = e; while (t.getCause() != null) t = t.getCause();
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length; i++) if (st[i].getMethodName().equals("frames")) return i;   // 抛出点到调用方之间的帧数
            return -2;
        }
    }
    public static void main(String[] a) {
        DataSource db = new Db();
        System.out.println("直接调用：抛出点到调用方之间 " + frames(db));
        System.out.println("三层装饰器：抛出点到调用方之间 " + frames(new Metrics(new Caching(new Logging(db)))));
        DataSource proxy = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, args) -> { try { return m.invoke(db, args); } catch (InvocationTargetException e) { throw e.getCause(); } });
        System.out.println("JDK 动态代理：抛出点到调用方之间 " + frames(proxy));
        try { new Metrics(new Caching(new Logging(db))).read("k"); } catch (RuntimeException e) {
            for (StackTraceElement el : e.getStackTrace()) { System.out.println("   at " + el); if (el.getMethodName().equals("main")) break; } }
    }
}
