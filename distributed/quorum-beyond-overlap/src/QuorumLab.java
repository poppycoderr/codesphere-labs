import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * N=3 的多副本读写，确定性模拟：W+R>N 时比较版本与不比较版本、W+R≤N、写入部分失败、带时钟偏差的最后写入获胜、
 * 版本向量发现并发写、宽松 quorum 下读写集合不相交、读自己的写。每个场景的副本状态与调用顺序都写在代码里。
 */
public class QuorumLab {
    static void out(String k, Object v) { System.out.println(k + "\t" + v); }

    record Versioned(String value, long ts, Map<String, Integer> vv) {
        Versioned(String value, long ts) { this(value, ts, Map.of()); }
    }
    static final class Replica {
        final String name; final List<Versioned> values = new ArrayList<>(); boolean up = true;
        Replica(String n, Versioned init) { name = n; values.add(init); }
        Versioned latestByTs() { return values.stream().max((a, b) -> Long.compare(a.ts(), b.ts())).orElseThrow(); }
    }
    static Map<String, Replica> cluster(String... names) {
        Map<String, Replica> m = new LinkedHashMap<>();
        for (String n : names) m.put(n, new Replica(n, new Versioned("v1", 1)));
        return m;
    }
    /** 向指定副本写入，返回成功确认的个数 */
    static int write(Map<String, Replica> c, Versioned v, String... targets) {
        int acks = 0;
        for (String t : targets) { Replica r = c.get(t); if (r.up) { r.values.clear(); r.values.add(v); acks++; } }
        return acks;
    }
    /** 最后写入获胜：时间戳大的覆盖小的 */
    static void writeLww(Replica r, Versioned v) { if (v.ts() > r.latestByTs().ts()) { r.values.clear(); r.values.add(v); } }
    static String readCompare(Map<String, Replica> c, String... from) {
        return java.util.Arrays.stream(from).map(c::get).map(Replica::latestByTs).max((a, b) -> Long.compare(a.ts(), b.ts())).orElseThrow().value();
    }
    static String readFirst(Map<String, Replica> c, String... from) { return c.get(from[0]).latestByTs().value(); }

    /** 版本向量：a 是否「发生在 b 之前或相同」 */
    static boolean leq(Map<String, Integer> a, Map<String, Integer> b) { return a.entrySet().stream().allMatch(e -> e.getValue() <= b.getOrDefault(e.getKey(), 0)); }
    static void writeVv(Replica r, Versioned v) {
        r.values.removeIf(old -> leq(old.vv(), v.vv()));                // 被新版本覆盖的旧值
        if (r.values.stream().noneMatch(old -> leq(v.vv(), old.vv()))) r.values.add(v);
    }
    static Map<String, Integer> vv(Object... kv) { Map<String, Integer> m = new TreeMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]); return m; }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version") + " N=3");

        // 一、W=2、R=2：读集合与写集合一定相交，但要比较版本
        var c = cluster("A", "B", "C");
        write(c, new Versioned("v2", 2), "A", "B");
        out("overlap.compare", "W=2 写入 v2 到 A、B（C 仍是 v1）；R=2 从 C、B 读，比较版本取最新：" + readCompare(c, "C", "B"));
        out("overlap.first", "同样从 C、B 读，取最先返回的 C：" + readFirst(c, "C", "B"));

        // 二、W=1、R=1：不相交
        c = cluster("A", "B", "C");
        write(c, new Versioned("v2", 2), "A");
        out("no_overlap", "W=1 写入 A，R=1 从 C 读：" + readCompare(c, "C"));

        // 三、写入部分失败
        c = cluster("A", "B", "C");
        c.get("B").up = false; c.get("C").up = false;
        int acks = write(c, new Versioned("v3", 3), "A", "B");
        c.get("B").up = true; c.get("C").up = true;
        out("partial_write", "W=2 写 v3，只有 A 确认（" + acks + " 个），客户端收到失败；随后 R=2 从 A、C 读：" + readCompare(c, "A", "C") + "；从 B、C 读：" + readCompare(c, "B", "C") + "：一次「失败」的写，有时读得到，有时读不到");

        // 四、最后写入获胜 + 时钟偏差
        Replica r = new Replica("A", new Versioned("v1", 1));
        writeLww(r, new Versioned("Y 写的 2", 95));                     // Y 在真实时间 95 写入，时钟准确
        writeLww(r, new Versioned("X 写的 1", 90));                     // X 在真实时间 100 写入，时钟慢了 10
        out("lww.skew", "Y 在真实时间 95 写入，X 在真实时间 100 写入但时钟慢 10：最后写入获胜保留的是「" + r.latestByTs().value() + "」，X 后发生的写入被静默丢弃");

        // 五、版本向量：发现并发写
        Replica s = new Replica("A", new Versioned("x=0", 0, vv("A", 1)));
        writeVv(s, new Versioned("x=1", 0, vv("A", 2)));                // 客户端 1 基于 {A:1}，经 A 写入
        writeVv(s, new Versioned("x=2", 0, vv("A", 1, "B", 1)));        // 客户端 2 也基于 {A:1}，经 B 写入
        out("vv.concurrent", "两个客户端都基于 {A:1} 写入，版本向量 {A:2} 与 {A:1,B:1} 互不包含：副本保留 " + s.values.stream().map(Versioned::value).toList() + "，交给读的一方合并");
        writeVv(s, new Versioned("x=3", 0, vv("A", 3, "B", 1)));        // 读到两个值、合并后写回
        out("vv.merge", "读到两个值、合并为 x=3 并以 {A:3,B:1} 写回：副本保留 " + s.values.stream().map(Versioned::value).toList());

        // 六、宽松 quorum：写到了备用节点
        c = cluster("A", "B", "C", "D");
        c.get("A").up = false; c.get("B").up = false;
        acks = write(c, new Versioned("v4", 4), "C", "D");                 // 首选 A、B、C，A、B 不可达，借用 D 凑够 W=2
        c.get("A").up = true; c.get("B").up = true;
        out("sloppy", "首选副本 A、B、C；A、B 不可达时写入落在 C 和备用节点 D（确认 " + acks + " 个）；A、B 恢复后、提示移交完成前，R=2 从 A、B 读：" + readCompare(c, "A", "B") + "：W+R=4>3，读写集合却不相交");

        // 七、读自己的写
        c = cluster("A", "B", "C");
        write(c, new Versioned("v5", 5), "A", "B");
        out("ryw.plain", "会话写入 v5（W=2 到 A、B），下一次 R=1 被路由到 C：" + readCompare(c, "C"));
        long minVersion = 5;
        String got = c.get("C").latestByTs().ts() >= minVersion ? c.get("C").latestByTs().value() : "拒绝（版本 " + c.get("C").latestByTs().ts() + " < " + minVersion + "）→ 改读 A：" + readCompare(c, "A");
        out("ryw.token", "会话记住自己写入的版本 5，读到更旧的版本就换副本：" + got);
    }
}
