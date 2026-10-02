import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * 读写锁与 StampedLock 的几个边界：升级与降级、重入、乐观读的校验、锁转换后的 stamp、全局写锁里的慢加载。
 * 除最后一个场景用闩锁协调两个线程外，其余都在单线程里顺序执行，结果是确定的。
 */
public class RwLab {

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        upgradeDowngrade();
        reentrancy();
        optimisticRead();
        conversion();
        cacheLoad();
    }

    static void upgradeDowngrade() {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.readLock().lock();
        boolean upgraded = rw.writeLock().tryLock();
        rw.readLock().unlock();
        out("rw.upgrade", "ReentrantReadWriteLock 持有读锁时 tryLock 写锁：" + upgraded + "（改用阻塞的 lock() 会永远等下去）");

        rw.writeLock().lock();
        boolean downgraded = rw.readLock().tryLock();
        rw.writeLock().unlock();
        out("rw.downgrade", "持有写锁时 tryLock 读锁：" + downgraded + "；释放写锁后仍持有读锁 " + rw.getReadHoldCount() + " 次");
        rw.readLock().unlock();
    }

    static void reentrancy() {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.writeLock().lock();
        boolean again = rw.writeLock().tryLock();
        out("reentrant.rw", "ReentrantReadWriteLock 同一线程第二次获取写锁：" + again + "，持有 " + rw.getWriteHoldCount() + " 次");

        StampedLock sl = new StampedLock();
        long w = sl.writeLock();
        long second = sl.tryWriteLock();
        long read = sl.tryReadLock();
        out("reentrant.stamped", "StampedLock 同一线程持有写锁时再 tryWriteLock 返回 " + second + "，tryReadLock 返回 " + read + "（0 表示失败；改用阻塞方法会自己等自己）");
        sl.unlockWrite(w);
    }

    static void optimisticRead() {
        StampedLock sl = new StampedLock();
        int[] point = {60, 40};                                  // 两个值之和恒为 100

        long stamp = sl.tryOptimisticRead();
        int x = point[0];                                        // 读了第一个值
        long w = sl.writeLock();                                 // 此时发生一次写入：60/40 → 30/70
        point[0] = 30; point[1] = 70;
        sl.unlockWrite(w);
        int y = point[1];                                        // 再读第二个值
        boolean valid = sl.validate(stamp);
        out("optimistic.invalid", "乐观读期间发生写入：读到 x=" + x + " y=" + y + "（和为 " + (x + y) + "），validate=" + valid);

        long r = sl.readLock();                                  // 校验失败后退回读锁重读
        x = point[0]; y = point[1];
        sl.unlockRead(r);
        out("optimistic.fallback", "退回读锁重读：x=" + x + " y=" + y + "（和为 " + (x + y) + "）");

        stamp = sl.tryOptimisticRead();
        x = point[0]; y = point[1];
        out("optimistic.valid", "乐观读期间没有写入：validate=" + sl.validate(stamp) + "，全程没有获取任何锁（isReadLocked=" + sl.isReadLocked() + "）");
    }

    static void conversion() {
        StampedLock sl = new StampedLock();
        long read = sl.readLock();
        long write = sl.tryConvertToWriteLock(read);
        String oldStamp;
        try { sl.unlockRead(read); oldStamp = "成功"; }
        catch (IllegalMonitorStateException e) { oldStamp = "抛出 IllegalMonitorStateException"; }
        out("convert.stamp", "只有一个读者时 tryConvertToWriteLock 成功=" + (write != 0) + "；之后用旧的读 stamp 解锁：" + oldStamp + "；isWriteLocked=" + sl.isWriteLocked());
        sl.unlockWrite(write);
        out("convert.new_stamp", "用转换返回的新 stamp 解锁后：isWriteLocked=" + sl.isWriteLocked());

        long r1 = sl.readLock(), r2 = sl.readLock();
        out("convert.two_readers", "有两个读者时 tryConvertToWriteLock 返回 " + sl.tryConvertToWriteLock(r1) + "（0 表示失败，原来的读锁仍然持有）");
        sl.unlockRead(r1); sl.unlockRead(r2);
    }

    /** 缓存未命中时在全局写锁里加载，与按键加载的对照。 */
    static void cacheLoad() throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        CountDownLatch loading = new CountDownLatch(1), finish = new CountDownLatch(1);
        Thread loader = Thread.ofPlatform().daemon().start(() -> {
            rw.writeLock().lock();                               // 键 A 未命中：拿全局写锁去加载
            try { loading.countDown(); finish.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            finally { rw.writeLock().unlock(); }
        });
        loading.await();
        boolean other = rw.readLock().tryLock(100, TimeUnit.MILLISECONDS);
        out("cache.global_write_lock", "键 A 在全局写锁里加载时，读已经在缓存里的键 B：100ms 内拿到读锁=" + other);
        finish.countDown(); loader.join();

        Map<String, String> cache = new ConcurrentHashMap<>();
        cache.put("B", "b");
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch inLoad = new CountDownLatch(1), release = new CountDownLatch(1);
        Runnable loadA = () -> cache.computeIfAbsent("A", k -> {
            loads.incrementAndGet();
            inLoad.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return "a";
        });
        Thread t1 = Thread.ofPlatform().daemon().start(loadA);
        inLoad.await();
        Thread t2 = Thread.ofPlatform().daemon().start(loadA);  // 第二个线程同时要键 A
        long t = System.nanoTime();
        String b = cache.get("B");
        long micros = (System.nanoTime() - t) / 1000;
        out("cache.per_key", "ConcurrentHashMap.computeIfAbsent 加载键 A 期间读键 B：得到 " + b + "，" + (micros < 50_000 ? "没有被阻塞" : "被阻塞 " + micros + "µs"));
        release.countDown(); t1.join(); t2.join();
        out("cache.load_once", "两个线程同时请求键 A：加载函数执行了 " + loads.get() + " 次，结果 " + cache.get("A"));
    }
}
