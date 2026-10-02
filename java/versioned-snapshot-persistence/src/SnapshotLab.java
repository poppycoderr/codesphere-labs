import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存状态加后台保存的四种写法：先清脏标记、保存成功后清脏标记、逐字段读取可变对象、版本化的不可变快照。
 * 存储是可注入失败与阻塞点的内存实现，场景由闩锁排定，结果是确定的。
 */
public class SnapshotLab {

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    /** 可变状态：两个字段之和应始终为 100。 */
    static final class Budget {
        int online = 60, offline = 40;
        String note = "v0";
    }

    /** 存储：记录最后一次成功写入的内容。 */
    static final class Store {
        volatile String saved = "online=60 offline=40 note=v0";
        volatile long savedVersion = 0;
        int failNext = 0;
        CountDownLatch entered, release;
        int calls = 0;

        void write(String content) throws Exception {
            calls++;
            if (entered != null) { entered.countDown(); release.await(2, TimeUnit.SECONDS); }
            if (failNext > 0) { failNext--; throw new IllegalStateException("store unavailable"); }
            saved = content;
        }

        /** 带版本的写入：只接受比已保存版本新的内容。 */
        boolean writeIfNewer(long version, String content) throws Exception {
            calls++;
            if (failNext > 0) { failNext--; throw new IllegalStateException("store unavailable"); }
            synchronized (this) {
                if (version <= savedVersion) return false;
                savedVersion = version; saved = content;
                return true;
            }
        }
    }

    static String render(Budget b) { return "online=" + b.online + " offline=" + b.offline + " note=" + b.note; }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        clearBeforeSave();
        clearAfterSave();
        tornSnapshot();
        versioned();
        staleWriter();
    }

    /** 1. 先清脏标记再保存：保存失败后，没有人知道还有东西没保存。 */
    static void clearBeforeSave() {
        Budget b = new Budget(); Store store = new Store();
        boolean[] dirty = {false};
        b.note = "v1"; dirty[0] = true;
        store.failNext = 1;
        for (int round = 1; round <= 3; round++) {              // 后台任务跑三轮
            if (!dirty[0]) continue;
            dirty[0] = false;
            try { store.write(render(b)); } catch (Exception e) { /* 记日志，等下一轮 */ }
        }
        out("clear_before", "修改为 v1，第一次保存失败，之后又跑了两轮：dirty=" + dirty[0] + "，存储里是 " + store.saved + "，共调用存储 " + store.calls + " 次");
    }

    /** 2. 保存成功后再清脏标记：保存期间到来的修改被这次「成功」一起清掉。 */
    static void clearAfterSave() throws Exception {
        Budget b = new Budget(); Store store = new Store();
        boolean[] dirty = {false};
        Object lock = new Object();
        synchronized (lock) { b.note = "v1"; dirty[0] = true; }
        store.entered = new CountDownLatch(1); store.release = new CountDownLatch(1);
        Thread saver = new Thread(() -> {
            String content;
            synchronized (lock) { if (!dirty[0]) return; content = render(b); }
            try { store.write(content); synchronized (lock) { dirty[0] = false; } } catch (Exception ignored) { }
        });
        saver.start();
        store.entered.await();                                   // v1 正在写入
        synchronized (lock) { b.note = "v2"; dirty[0] = true; }  // 用户又改了一次
        store.release.countDown();
        saver.join();
        store.entered = null;
        int later = 0;
        for (int round = 0; round < 2; round++) { synchronized (lock) { if (dirty[0]) later++; } }
        out("clear_after", "v1 保存期间修改为 v2，保存成功后清标记：dirty=" + dirty[0] + "，存储里是 " + store.saved + "，之后两轮触发保存 " + later + " 次");
    }

    /** 3. 保存线程逐字段读取可变对象：读到一半时发生修改，写进存储的内容自相矛盾。 */
    static void tornSnapshot() throws Exception {
        Budget b = new Budget(); Store store = new Store();
        CountDownLatch readOnline = new CountDownLatch(1), moved = new CountDownLatch(1);
        Thread saver = new Thread(() -> {
            try {
                int online = b.online;                           // 读了第一个字段
                readOnline.countDown(); moved.await();
                int offline = b.offline;                         // 读第二个字段时，另一个线程已经改完
                store.write("online=" + online + " offline=" + offline + " note=" + b.note);
            } catch (Exception ignored) { }
        });
        saver.start();
        readOnline.await();
        b.online = 30; b.offline = 70;                           // 把 30 从线上挪到线下，总和不变
        moved.countDown();
        saver.join();
        String[] p = store.saved.split("[ =]");
        out("torn", "保存线程读到一半时发生了「线上挪 30 到线下」：存储里是 " + store.saved + "，两项之和 " + (Integer.parseInt(p[1]) + Integer.parseInt(p[3])) + "（内存里始终是 100）");
    }

    /** 不可变快照：版本号与内容一起发布。 */
    record Snapshot(long version, int online, int offline, String note) {
        String render() { return "online=" + online + " offline=" + offline + " note=" + note; }
    }

    /** 4. 版本化快照：失败不前进，保存期间的新版本下一轮继续保存。 */
    static void versioned() throws Exception {
        AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(0, 60, 40, "v0"));
        Store store = new Store();
        long[] persisted = {0};
        Runnable saveRound = () -> {
            Snapshot s = current.get();                          // 一次读取拿到自洽的整体
            if (s.version() <= persisted[0]) return;
            try { if (store.writeIfNewer(s.version(), s.render())) persisted[0] = Math.max(persisted[0], s.version()); }
            catch (Exception e) { /* 不前进，下一轮重试 */ }
        };
        current.updateAndGet(s -> new Snapshot(s.version() + 1, 30, 70, "v1"));
        store.failNext = 1;
        saveRound.run();
        String afterFail = "已保存版本 " + persisted[0];
        saveRound.run();
        out("versioned.retry", "修改为 v1，第一次保存失败：" + afterFail + "；下一轮重试后已保存版本 " + persisted[0] + "，存储里是 " + store.saved);

        current.updateAndGet(s -> new Snapshot(s.version() + 1, 30, 70, "v2"));
        Snapshot captured = current.get();                       // 第 2 版的保存开始
        current.updateAndGet(s -> new Snapshot(s.version() + 1, 20, 80, "v3"));   // 保存期间又改成 v3
        store.writeIfNewer(captured.version(), captured.render());
        persisted[0] = Math.max(persisted[0], captured.version());
        String mid = "已保存版本 " + persisted[0] + "，内存版本 " + current.get().version();
        saveRound.run();
        out("versioned.during_save", "第 2 版保存期间又修改为第 3 版：保存完成时" + mid + "；下一轮之后已保存版本 " + persisted[0] + "，存储里是 " + store.saved);
    }

    /** 5. 两个保存者乱序完成：旧版本后到。 */
    static void staleWriter() throws Exception {
        Store plain = new Store(), guarded = new Store();
        Snapshot v4 = new Snapshot(4, 10, 90, "v4"), v5 = new Snapshot(5, 0, 100, "v5");
        plain.write(v5.render()); plain.write(v4.render());      // 新版本先写完，旧版本的写入后到
        boolean a = guarded.writeIfNewer(v5.version(), v5.render());
        boolean b = guarded.writeIfNewer(v4.version(), v4.render());
        out("stale.plain", "第 5 版先写入、第 4 版的写入后到，存储不比较版本：存储里是 " + plain.saved);
        out("stale.guarded", "同样的顺序，存储只接受更新的版本：两次写入分别返回 " + a + "、" + b + "，存储里是 " + guarded.saved);
    }
}
