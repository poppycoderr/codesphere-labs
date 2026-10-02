import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 用信号量限制并发与「借出一个具体资源」之间的差别：许可只是计数，资源的身份、归属与好坏要另外管理。
 * 全部场景单线程顺序执行，结果是确定的。
 */
public class LeaseLab {

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    /** 被借用的资源：有身份，可能损坏。 */
    static final class Conn {
        final int id;
        boolean broken;
        Conn(int id) { this.id = id; }
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        sameInstance();
        releaseWithoutAcquire();
        leak();
        doubleReturn();
        useAfterReturn();
        brokenResource();
        leasePool();
    }

    /** 1. 2 个许可，池里却是同一个对象放了两次。 */
    static void sameInstance() throws Exception {
        Semaphore permits = new Semaphore(2);
        Conn shared = new Conn(1);
        List<Conn> pool = new ArrayList<>(List.of(shared, shared));
        permits.acquire(); Conn a = pool.remove(0);
        permits.acquire(); Conn b = pool.remove(0);
        out("permits.same_instance", "2 个许可都借出：两个借用者拿到的是" + (a == b ? "同一个对象" : "不同的对象") + "（id " + a.id + " 与 " + b.id + "）");
    }

    /** 2. 没拿到许可也归还：许可数超过上限。 */
    static void releaseWithoutAcquire() throws Exception {
        Semaphore permits = new Semaphore(2);
        permits.acquire(); permits.acquire();                    // 许可已经借完
        boolean got = false;
        try { got = permits.tryAcquire(10, TimeUnit.MILLISECONDS); }
        finally { permits.release(); }                           // 不管拿没拿到都归还
        permits.release(); permits.release();                    // 前两个借用者正常归还
        out("permits.release_without_acquire", "上限 2，一次 tryAcquire 超时（返回 " + got + "）后仍在 finally 里 release：全部归还后可用许可 " + permits.availablePermits());
    }

    /** 3. 借出后抛异常，没有 finally：许可一去不回。 */
    static void leak() throws Exception {
        Semaphore permits = new Semaphore(2);
        for (int i = 0; i < 2; i++) {
            try { permits.acquire(); throw new IllegalStateException("business error"); /* 没有 finally 归还 */ }
            catch (IllegalStateException ignored) { }
        }
        out("permits.leak", "两次借出后都因异常没有归还：可用许可 " + permits.availablePermits() + "，再借一次 tryAcquire(50ms) 返回 " + permits.tryAcquire(50, TimeUnit.MILLISECONDS));
    }

    /** 4. 同一个资源被归还两次：之后两个借用者拿到同一个对象。 */
    static void doubleReturn() throws Exception {
        BlockingQueue<Conn> pool = new ArrayBlockingQueue<>(4);
        pool.add(new Conn(1)); pool.add(new Conn(2));
        Conn c = pool.take();
        pool.add(c); pool.add(c);                                // close() 被调用了两次
        Conn x = pool.take(), y = pool.take(), z = pool.take();
        out("return.twice", "id 1 被归还两次后池里有 3 项；依次借出 id " + x.id + "、" + y.id + "、" + z.id + "：其中两个借用者同时持有 id 1 = " + (y == z || x == y || x == z));
    }

    /** 5. 归还之后继续使用手里的引用。 */
    static void useAfterReturn() throws Exception {
        BlockingQueue<Conn> pool = new ArrayBlockingQueue<>(2);
        pool.add(new Conn(1));
        Conn first = pool.take();
        pool.add(first);                                         // 归还，但变量还在
        Conn second = pool.take();                               // 另一个借用者借到同一个对象
        out("return.use_after", "归还后原借用者手里的引用与新借用者拿到的是同一个对象 = " + (first == second) + "：两方都能调用它");
    }

    /** 6. 损坏的资源被原样放回。 */
    static void brokenResource() throws Exception {
        BlockingQueue<Conn> pool = new ArrayBlockingQueue<>(2);
        pool.add(new Conn(1));
        Conn c = pool.take();
        c.broken = true;                                         // 使用中连接断了
        pool.add(c);
        out("return.broken", "损坏的资源原样归还：下一个借用者拿到 id " + pool.peek().id + "，broken=" + pool.peek().broken);
    }

    /** 带租约的池：资源各不相同，租约只能关闭一次，关闭后失效，损坏的资源被替换。 */
    static final class Pool {
        private final BlockingQueue<Conn> idle;
        private final Supplier<Conn> factory;
        int replaced;

        Pool(int size, Supplier<Conn> factory) {
            this.idle = new ArrayBlockingQueue<>(size);
            this.factory = factory;
            for (int i = 0; i < size; i++) idle.add(factory.get());
        }

        Lease borrow(long timeoutMs) throws InterruptedException {
            Conn c = idle.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (c == null) throw new IllegalStateException("pool exhausted");
            return new Lease(c);
        }

        int idleCount() { return idle.size(); }

        final class Lease implements AutoCloseable {
            private final Conn conn;
            private final AtomicBoolean closed = new AtomicBoolean();
            Lease(Conn conn) { this.conn = conn; }

            Conn conn() {
                if (closed.get()) throw new IllegalStateException("lease closed");
                return conn;
            }

            @Override public void close() {
                if (!closed.compareAndSet(false, true)) return;  // 第二次关闭什么都不做
                if (conn.broken) { replaced++; idle.add(factory.get()); } else { idle.add(conn); }
            }
        }
    }

    static void leasePool() throws Exception {
        int[] nextId = {0};
        Pool pool = new Pool(2, () -> new Conn(++nextId[0]));

        Pool.Lease a = pool.borrow(10), b = pool.borrow(10);
        out("lease.distinct", "池大小 2，两个租约拿到 id " + a.conn().id + " 与 " + b.conn().id + "；第三次借用：" + attempt(() -> pool.borrow(10).conn().id));
        a.close(); a.close();
        out("lease.close_twice", "同一个租约 close 两次：空闲资源 " + pool.idleCount() + " 个");
        out("lease.use_after_close", "关闭后再通过租约取资源：" + attempt(() -> a.conn().id));
        b.close();

        try (Pool.Lease l = pool.borrow(10)) { l.conn(); throw new IllegalStateException("business error"); }
        catch (IllegalStateException ignored) { }
        out("lease.exception", "try-with-resources 中业务抛异常：空闲资源 " + pool.idleCount() + " 个");

        int brokenId;
        try (Pool.Lease l = pool.borrow(10)) { l.conn().broken = true; brokenId = l.conn().id; }
        List<Integer> ids = new ArrayList<>();
        try (Pool.Lease x = pool.borrow(10); Pool.Lease y = pool.borrow(10)) { ids.add(x.conn().id); ids.add(y.conn().id); }
        out("lease.broken", "id " + brokenId + " 在使用中损坏后归还：被替换 " + pool.replaced + " 个；之后池里的资源是 id " + ids + "，不含 id " + brokenId + " = " + !ids.contains(brokenId));
    }

    interface Action { Object run() throws Exception; }

    static String attempt(Action a) {
        try { return "得到 " + a.run(); } catch (Exception e) { return "抛出 " + e.getClass().getSimpleName() + "（" + e.getMessage() + "）"; }
    }
}
