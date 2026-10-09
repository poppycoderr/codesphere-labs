import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 雪花算法式的 ID：41 位毫秒时间戳、10 位机器号、12 位序列号。
 * 时钟由测试代码给出，所以时钟回拨、同一毫秒内的大量生成、两个实例同号都可以按固定的顺序构造，输出确定。
 */
public class SnowflakeLab {
    static final long EPOCH = 1_577_836_800_000L;                     // 2020-01-01T00:00:00Z
    static final int WORKER_BITS = 10, SEQ_BITS = 12;
    static final long MAX_SEQ = (1L << SEQ_BITS) - 1;

    /** 没有任何保护的写法：时间戳相同就把序列号加一（溢出后回绕），否则序列号归零 */
    static final class Naive {
        final long worker; final LongSupplier clock; long last = -1, seq;
        Naive(long worker, LongSupplier clock) { this.worker = worker; this.clock = clock; }
        long next() {
            long now = clock.getAsLong();
            seq = now == last ? (seq + 1) & MAX_SEQ : 0;
            last = now;
            return (now - EPOCH) << (WORKER_BITS + SEQ_BITS) | worker << SEQ_BITS | seq;
        }
    }

    /** 带保护的写法：时钟回拨时拒绝；同一毫秒序列号用完时等到下一毫秒 */
    static final class Guarded {
        final long worker; final LongSupplier clock; final Runnable waitOneMs; long last, seq;
        Guarded(long worker, LongSupplier clock, Runnable waitOneMs, long lastPersisted) { this.worker = worker; this.clock = clock; this.waitOneMs = waitOneMs; this.last = lastPersisted; }
        long next() {
            long now = clock.getAsLong();
            if (now < last) throw new IllegalStateException("clock moved backwards by " + (last - now) + " ms");
            if (now == last) {
                if (seq == MAX_SEQ) { while ((now = clock.getAsLong()) <= last) waitOneMs.run(); seq = 0; }
                else seq++;
            } else seq = 0;
            last = now;
            return (now - EPOCH) << (WORKER_BITS + SEQ_BITS) | worker << SEQ_BITS | seq;
        }
    }

    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static int duplicates(List<Long> ids) { Set<Long> seen = new HashSet<>(); int d = 0; for (long id : ids) if (!seen.add(id)) d++; return d; }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));
        long t0 = EPOCH + 200L * 24 * 3600 * 1000;                     // 纪元之后第 200 天
        long[] now = {t0};

        // 一、时钟回拨：每毫秒生成 100 个，生成 10 毫秒后时钟被拨回 5 毫秒，再生成 10 毫秒
        Naive naive = new Naive(7, () -> now[0]);
        List<Long> ids = new ArrayList<>();
        for (int ms = 0; ms < 10; ms++) { now[0] = t0 + ms; for (int i = 0; i < 100; i++) ids.add(naive.next()); }
        for (int ms = 5; ms < 15; ms++) { now[0] = t0 + ms; for (int i = 0; i < 100; i++) ids.add(naive.next()); }
        out("rollback.naive", "生成 2000 个 ID，中途时钟回拨 5 毫秒：重复 " + duplicates(ids) + " 个");

        now[0] = t0;
        Guarded guarded = new Guarded(7, () -> now[0], () -> now[0]++, -1);
        for (int ms = 0; ms < 10; ms++) { now[0] = t0 + ms; for (int i = 0; i < 100; i++) guarded.next(); }
        now[0] = t0 + 4;
        String r;
        try { guarded.next(); r = "生成成功"; } catch (IllegalStateException e) { r = "抛出 IllegalStateException（" + e.getMessage() + "）"; }
        out("rollback.guarded", "带回拨检查的生成器在时钟回拨后：" + r);

        // 二、重启：回拨检查依赖内存里的「上一次时间戳」，进程重启后这个值没了
        ids.clear();
        now[0] = t0;
        Guarded before = new Guarded(7, () -> now[0], () -> now[0]++, -1);
        for (int ms = 0; ms < 10; ms++) { now[0] = t0 + ms; for (int i = 0; i < 100; i++) ids.add(before.next()); }
        long persisted = before.last;
        now[0] = t0 + 5;                                               // 重启期间时钟被校正，回到了 5 毫秒前
        Guarded restarted = new Guarded(7, () -> now[0], () -> now[0]++, -1);
        for (int ms = 5; ms < 15; ms++) { now[0] = t0 + ms; for (int i = 0; i < 100; i++) ids.add(restarted.next()); }
        out("restart.memory_only", "带回拨检查的生成器重启（重启期间时钟回退 5 毫秒）后继续生成：重复 " + duplicates(ids) + " 个");
        now[0] = t0 + 5;
        Guarded restored = new Guarded(7, () -> now[0], () -> now[0]++, persisted);
        try { restored.next(); r = "生成成功"; } catch (IllegalStateException e) { r = "抛出 IllegalStateException（" + e.getMessage() + "）"; }
        out("restart.persisted", "重启时恢复了上次持久化的时间戳：" + r);

        // 三、同一毫秒里生成超过 4096 个
        now[0] = t0;
        Naive burst = new Naive(7, () -> now[0]);
        ids.clear();
        for (int i = 0; i < 5000; i++) ids.add(burst.next());
        out("burst.naive", "同一毫秒内生成 5000 个，序列号直接回绕：重复 " + duplicates(ids) + " 个");
        now[0] = t0;
        Guarded burstGuarded = new Guarded(7, () -> now[0], () -> now[0]++, -1);
        ids.clear();
        for (int i = 0; i < 5000; i++) ids.add(burstGuarded.next());
        out("burst.guarded", "序列号用完时等到下一毫秒：重复 " + duplicates(ids) + " 个，生成器等过的毫秒数 " + (now[0] - t0));

        // 四、两个实例用了同一个机器号
        now[0] = t0;
        Guarded a = new Guarded(5, () -> now[0], () -> now[0]++, -1), b = new Guarded(5, () -> now[0], () -> now[0]++, -1);
        ids.clear();
        for (int i = 0; i < 100; i++) { ids.add(a.next()); ids.add(b.next()); }
        out("worker.same_id", "两个实例都用机器号 5，同一毫秒各生成 100 个：重复 " + duplicates(ids) + " 个");
        out("worker.ip_low_bits", "机器号取 IP 的低 10 位：10.0.0.5 得到 " + ((0 << 8 | 5) & 1023) + "，10.0.4.5 得到 " + ((4 << 8 | 5) & 1023));
        for (int n : new int[]{10, 30, 50, 100}) {
            double p = 1;
            for (int i = 0; i < n; i++) p *= 1 - i / 1024.0;
            out("worker.hash_collision_" + n, n + " 个实例各自把主机名哈希到 1024 个机器号：至少两个实例同号的概率 " + String.format("%.1f%%", 100 * (1 - p)));
        }

        // 五、多个实例的 ID 合在一起只是大致按时间排序
        long[] clockA = {t0}, clockB = {t0 + 3};                       // 实例 B 的时钟快 3 毫秒
        Guarded ga = new Guarded(1, () -> clockA[0], () -> clockA[0]++, -1), gb = new Guarded(2, () -> clockB[0], () -> clockB[0]++, -1);
        long first = gb.next();                                         // 真实时间第 0 毫秒：B 先生成
        clockA[0] += 2; clockB[0] += 2;
        long second = ga.next();                                        // 真实时间第 2 毫秒：A 后生成
        out("order.across_workers", "实例 B 的时钟快 3 毫秒：B 先生成的 ID 比 A 两毫秒之后生成的 ID 更大 = " + (first > second));

        // 六、位数预算
        long maxMs = (1L << 41) - 1;
        out("bits.lifetime", "41 位毫秒时间戳可用 " + String.format("%.1f", maxMs / 1000.0 / 3600 / 24 / 365.25) + " 年；纪元取 2020-01-01 时用到 "
                + java.time.Instant.ofEpochMilli(EPOCH + maxMs).toString().substring(0, 10));
        long js = 1L << 53;
        out("bits.js_safe", "ID 超过 2^53 发生在纪元之后 " + String.format("%.1f", (js >> 22) / 1000.0 / 3600 / 24) + " 天；第 200 天的一个 ID " + ids.get(2)
                + " 转成 double 再转回是 " + (long) (double) ids.get(2));
        out("bits.rate", "单个机器号每毫秒最多 " + (MAX_SEQ + 1) + " 个，即每秒 " + (MAX_SEQ + 1) * 1000 + " 个；机器号最多 " + (1 << WORKER_BITS) + " 个");
    }
}
