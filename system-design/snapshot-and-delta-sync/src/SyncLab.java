import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「快照 + 增量」同步的六种情形：副本从来源的一份快照起步，再按序号应用增量。
 * 来源、网络与副本都在一个线程里模拟，事件顺序由代码排定，结果是确定的。
 */
public class SyncLab {

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    /** 一条增量：把 key 的库存加上 delta（可以为负）；序号从 1 连续递增。 */
    record Delta(long seq, String key, int delta) { }

    /** 快照：某个序号时刻的完整状态。 */
    record Snapshot(long seq, Map<String, Integer> state) { }

    /** 来源：权威状态，每次修改产生一条增量。 */
    static final class Source {
        final TreeMap<String, Integer> state = new TreeMap<>();
        final List<Delta> log = new ArrayList<>();

        Delta change(String key, int delta) {
            state.merge(key, delta, Integer::sum);
            Delta d = new Delta(log.size() + 1, key, delta);
            log.add(d);
            return d;
        }

        Snapshot snapshot() { return new Snapshot(log.size(), new TreeMap<>(state)); }
    }

    /** 副本：检查序号的版本。 */
    static final class Replica {
        final TreeMap<String, Integer> state = new TreeMap<>();
        long lastSeq;
        int duplicates, gaps;
        final TreeMap<Long, Delta> pending = new TreeMap<>();    // 提前到达、等前面补齐的增量

        void load(Snapshot s) { state.clear(); state.putAll(s.state()); lastSeq = s.seq(); pending.clear(); }

        /** 返回 false 表示发现缺口，需要重新同步。 */
        boolean apply(Delta d, int reorderWindow) {
            if (d.seq() <= lastSeq) { duplicates++; return true; }          // 快照已包含，或重复投递
            pending.put(d.seq(), d);
            while (!pending.isEmpty() && pending.firstKey() == lastSeq + 1) {
                Delta next = pending.pollFirstEntry().getValue();
                state.merge(next.key(), next.delta(), Integer::sum);
                lastSeq = next.seq();
            }
            if (pending.size() > reorderWindow) { gaps++; return false; }   // 等不到缺的那条
            return true;
        }
    }

    static void applyBlindly(Map<String, Integer> state, Delta d) { state.merge(d.key(), d.delta(), Integer::sum); }

    static Source seeded() {
        Source s = new Source();
        s.change("A", 10); s.change("B", 20); s.change("C", 30);            // 序号 1—3
        return s;
    }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));
        snapshotThenSubscribe();
        subscribeThenSnapshot();
        duplicateAndGap();
        reorder();
        truncatedState();
    }

    /** 1. 先取快照、后订阅：两步之间的增量谁也没收到。 */
    static void snapshotThenSubscribe() {
        Source src = seeded();
        Snapshot snap = src.snapshot();                                      // 快照在序号 3
        src.change("A", -4);                                                 // 序号 4：发生在快照之后、订阅之前
        Map<String, Integer> replica = new TreeMap<>(snap.state());
        applyBlindly(replica, src.change("B", 5));                           // 订阅之后只收到序号 5
        out("order.snapshot_first", "先取快照（序号 3）后订阅，其间来源产生了序号 4：副本 " + replica + "，来源 " + src.state + "，一致=" + replica.equals(src.state));
    }

    /** 2. 先订阅并缓存、后取快照：缓存里有快照已经包含的增量。 */
    static void subscribeThenSnapshot() {
        Source src = seeded();
        List<Delta> buffered = new ArrayList<>();
        buffered.add(src.change("A", -4));                                   // 序号 4：订阅后、快照前
        Snapshot snap = src.snapshot();                                      // 快照在序号 4，已经包含它
        buffered.add(src.change("B", 5));                                    // 序号 5

        Map<String, Integer> blind = new TreeMap<>(snap.state());
        buffered.forEach(d -> applyBlindly(blind, d));
        out("order.subscribe_first.blind", "先订阅后取快照（序号 4），缓存的增量全部应用：副本 " + blind + "，来源 " + src.state + "，一致=" + blind.equals(src.state));

        Replica r = new Replica();
        r.load(snap);
        buffered.forEach(d -> r.apply(d, 0));
        out("order.subscribe_first.by_seq", "同样的顺序，丢弃序号不大于快照序号的增量：副本 " + r.state + "，丢弃 " + r.duplicates + " 条，一致=" + r.state.equals(src.state));
    }

    /** 3. 重复投递与丢失一条。 */
    static void duplicateAndGap() {
        Source src = seeded();
        Snapshot snap = src.snapshot();
        Delta d4 = src.change("A", -4), d5 = src.change("B", 5), d6 = src.change("C", -1);

        Map<String, Integer> blind = new TreeMap<>(snap.state());
        for (Delta d : List.of(d4, d4, d6)) applyBlindly(blind, d);          // 序号 4 重复，序号 5 丢失
        out("faults.blind", "序号 4 重复投递、序号 5 丢失，不检查序号：副本 " + blind + "，来源 " + src.state + "，一致=" + blind.equals(src.state) + "，副本没有任何报错");

        Replica r = new Replica();
        r.load(snap);
        boolean ok = true;
        for (Delta d : List.of(d4, d4, d6)) ok &= r.apply(d, 0);
        String detected = "忽略重复 " + r.duplicates + " 条，发现缺口 " + r.gaps + " 次（收到序号 6 时还缺序号 5）";
        if (!ok) r.load(src.snapshot());                                     // 重新同步：再取一次快照
        out("faults.by_seq", "同样的输入，检查序号：" + detected + "；重新取快照后副本 " + r.state + "，一致=" + r.state.equals(src.state));
    }

    /** 4. 乱序到达：允许短暂等待前面的增量。 */
    static void reorder() {
        Source src = seeded();
        Snapshot snap = src.snapshot();
        Delta d4 = src.change("A", -4), d5 = src.change("B", 5), d6 = src.change("C", -1);
        Replica r = new Replica();
        r.load(snap);
        boolean ok = true;
        for (Delta d : List.of(d5, d6, d4)) ok &= r.apply(d, 2);             // 5、6 先到，4 后到；最多暂存 2 条
        out("reorder.window", "序号 5、6 先到、4 后到，最多暂存 2 条：需要重新同步=" + !ok + "，副本 " + r.state + "，一致=" + r.state.equals(src.state));
    }

    /** 5. 副本只保留「库存最多的前 2 个」：被裁掉的数据后面还会用到。 */
    static void truncatedState() {
        Source src = seeded();                                               // A=10 B=20 C=30
        Map<String, Integer> top2 = new TreeMap<>(src.snapshot().state());
        top2.remove("A");                                                    // 只留库存最多的两个：B、C
        Delta sold = src.change("C", -30);                                   // C 卖完
        applyBlindly(top2, sold);
        top2.values().removeIf(v -> v == 0);
        TreeMap<String, Integer> truth = new TreeMap<>(src.state);
        truth.values().removeIf(v -> v == 0);
        out("truncate", "副本只保存库存最多的前 2 个（B、C），随后 C 售罄：副本里还剩 " + top2 + "；来源里有库存的是 " + truth + "，前 2 个应该是 A 和 B");
    }
}
