import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

/**
 * Cluster 实验的业务客户端，使用 Jedis 5.2.0 的 JedisCluster（自动维护 slot 映射、处理 MOVED 与 ASK）。
 *   crossslot <输出文件>：同一 slot 与不同 slot 的 MGET、事务与 Lua
 *   workload <输出前缀> <hash tag>：持续读写该 hash tag 下的 2000 个 key 与其他 slot 的 key，每次读取校验值，出现 <前缀>.stop 时结束
 *   writer <输出前缀>：每秒 200 次顺序写入 fo:<seq>（分散在各个 slot），记录每次写入的结果，出现 <前缀>.stop 时结束
 */
public class ClusterClient {

    static JedisCluster connect() {
        Set<HostAndPort> seeds = Set.of(new HostAndPort("n1", 6379), new HostAndPort("n2", 6379), new HostAndPort("n3", 6379));
        return new JedisCluster(seeds,
                DefaultJedisClientConfig.builder().connectionTimeoutMillis(1000).socketTimeoutMillis(1000).build(),
                10, Duration.ofSeconds(20));
    }

    static String clean(Throwable e) {
        String m = e.getClass().getSimpleName() + ": " + e.getMessage();
        return m.replace('\t', ' ').replace('\n', ' ');
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "crossslot" -> crossSlot(Path.of(args[1]));
            case "workload" -> workload(args[1], args[2]);
            case "writer" -> writer(args[1]);
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void crossSlot(Path out) throws Exception {
        try (JedisCluster c = connect();
             PrintStream p = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
            p.println("case\tresult");
            c.set("user:1001:profile", "p");
            c.set("user:1001:orders", "o");
            c.set("{user:1001}:profile", "p");
            c.set("{user:1001}:orders", "o");
            try {
                p.println("mget_different_slots\t" + c.mget("user:1001:profile", "user:1001:orders"));
            } catch (Exception e) {
                p.println("mget_different_slots\t" + clean(e));
            }
            p.println("mget_hash_tag\t" + c.mget("{user:1001}:profile", "{user:1001}:orders"));
            String lua = "return {redis.call('GET', KEYS[1]), redis.call('GET', KEYS[2])}";
            try {
                p.println("eval_different_slots\t" + c.eval(lua, 2, "user:1001:profile", "user:1001:orders"));
            } catch (Exception e) {
                p.println("eval_different_slots\t" + clean(e));
            }
            p.println("eval_hash_tag\t" + c.eval(lua, 2, "{user:1001}:profile", "{user:1001}:orders"));
        }
    }

    /** 读写交替：先写 v<轮次>，再读回校验。只统计客户端最终看到的错误；Jedis 内部跟随 MOVED / ASK 的重试不计为错误。 */
    static void workload(String prefix, String tag) throws Exception {
        Path stop = Path.of(prefix + ".stop");
        try (JedisCluster c = connect();
             PrintStream p = new PrintStream(Files.newOutputStream(Path.of(prefix + "-workload.tsv")), true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(Files.newOutputStream(Path.of(prefix + "-errors.tsv")), true, StandardCharsets.UTF_8)) {
            p.println("second\tops\terrors\twrong_values\tp50_us\tp99_us\tmax_us");
            err.println("at_ms\tkey\terror");
            long start = System.currentTimeMillis();
            long round = 0;
            while (!Files.exists(stop)) {
                long secStart = System.currentTimeMillis();
                List<Long> lat = new ArrayList<>();
                int errors = 0;
                int wrong = 0;
                while (System.currentTimeMillis() - secStart < 1000) {
                    round++;
                    String key = (round % 2 == 0) ? "{" + tag + "}:" + (round % 2000) : "other:" + (round % 20000);
                    String value = "v" + round;
                    long t0 = System.nanoTime();
                    try {
                        c.set(key, value);
                        String got = c.get(key);
                        if (!value.equals(got)) wrong++;
                    } catch (Exception e) {
                        errors++;
                        err.println(System.currentTimeMillis() + "\t" + key + "\t" + clean(e));
                    }
                    lat.add((System.nanoTime() - t0) / 1000);
                }
                lat.sort(null);
                p.printf("%d\t%d\t%d\t%d\t%d\t%d\t%d%n", (secStart - start) / 1000, lat.size() * 2, errors, wrong,
                        lat.get(lat.size() / 2), lat.get((int) (lat.size() * 0.99)), lat.get(lat.size() - 1));
            }
        }
    }

    static void writer(String prefix) throws Exception {
        Path stop = Path.of(prefix + ".stop");
        try (JedisCluster c = connect();
             PrintStream p = new PrintStream(Files.newOutputStream(Path.of(prefix + "-writes.tsv")), true, StandardCharsets.UTF_8)) {
            p.println("seq\tstatus\tat_ms\tlatency_us\tdetail");
            long start = System.nanoTime();
            for (long seq = 1; !Files.exists(stop); seq++) {
                long due = start + (seq - 1) * 5_000_000L;
                while (System.nanoTime() < due) Thread.onSpinWait();
                long t0 = System.nanoTime();
                try {
                    c.set("fo:" + seq, String.valueOf(seq));
                    p.println(seq + "\tack\t" + System.currentTimeMillis() + "\t" + (System.nanoTime() - t0) / 1000 + "\t");
                } catch (Exception e) {
                    p.println(seq + "\terror\t" + System.currentTimeMillis() + "\t" + (System.nanoTime() - t0) / 1000 + "\t" + clean(e));
                }
            }
        }
    }
}
