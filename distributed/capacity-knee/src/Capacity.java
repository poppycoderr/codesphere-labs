import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主键点查与主键更新在不同并发下的吞吐与延迟，输出为「键<TAB>事实」。每个线程一条连接，闭环压测：发出、等结果、再发下一个。
 * 运行：java -cp <mysql-connector> src/Capacity.java，连接 127.0.0.1:3306/labs。
 */
public class Capacity {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&useServerPrepStmts=true&cachePrepStmts=true";
    static final int WARMUP_MS = 3000;
    static final int MEASURE_MS = 8000;

    public static void main(String[] args) throws Exception {
        run("read", 16, 3000);                                   // 预热 Buffer Pool 与 JIT，不输出
        for (int c : new int[] {1, 4, 8, 16, 32, 64, 128, 256}) {
            report("read", c, run("read", c, MEASURE_MS));
        }
        for (int c : new int[] {1, 8, 32, 64}) {
            report("write", c, run("write", c, MEASURE_MS));
        }
    }

    record Result(long ops, long[] latNanos, long elapsedNanos) {
    }

    static Result run(String kind, int concurrency, int measureMs) throws Exception {
        AtomicBoolean measuring = new AtomicBoolean();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(concurrency);
        List<long[]> lats = new ArrayList<>();
        List<int[]> counts = new ArrayList<>();
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < concurrency; t++) {
            long[] lat = new long[4_000_000 / concurrency + 1000];
            int[] n = new int[1];
            lats.add(lat);
            counts.add(n);
            ts.add(Thread.ofPlatform().start(() -> {
                try (Connection c = DriverManager.getConnection(URL, "root", "example_password");
                     PreparedStatement read = c.prepareStatement("SELECT balance, name FROM account WHERE id = ?");
                     PreparedStatement write = c.prepareStatement("UPDATE account SET balance = balance + 1 WHERE id = ?")) {
                    ready.countDown();
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    while (!stop.get()) {
                        long id = r.nextLong(1, 100_001);
                        long s = System.nanoTime();
                        if (kind.equals("read")) {
                            read.setLong(1, id);
                            try (ResultSet rs = read.executeQuery()) {
                                rs.next();
                            }
                        } else {
                            write.setLong(1, id);
                            write.executeUpdate();
                        }
                        long d = System.nanoTime() - s;
                        if (measuring.get() && n[0] < lat.length) {
                            lat[n[0]++] = d;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        ready.await();
        Thread.sleep(WARMUP_MS);
        measuring.set(true);
        long begin = System.nanoTime();
        Thread.sleep(measureMs);
        measuring.set(false);
        long elapsed = System.nanoTime() - begin;
        stop.set(true);
        for (Thread t : ts) {
            t.join();
        }
        long total = counts.stream().mapToLong(x -> x[0]).sum();
        long[] all = new long[(int) total];
        int k = 0;
        for (int i = 0; i < lats.size(); i++) {
            System.arraycopy(lats.get(i), 0, all, k, counts.get(i)[0]);
            k += counts.get(i)[0];
        }
        return new Result(total, all, elapsed);
    }

    static void report(String kind, int c, Result r) {
        long[] a = r.latNanos();
        Arrays.sort(a);
        double qps = r.ops() / (r.elapsedNanos() / 1e9);
        out(kind + "." + c, "%s 并发 %d：%,.0f QPS，p50 %.2f ms，p99 %.2f ms".formatted(
                kind.equals("read") ? "主键点查" : "主键更新", c, qps, a[a.length / 2] / 1e6, a[(int) (a.length * 0.99)] / 1e6));
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
