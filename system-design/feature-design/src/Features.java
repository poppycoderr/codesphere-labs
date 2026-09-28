import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 典型业务功能的几个实测，输出为「键<TAB>事实」：
 * 1. ZSET 同分时的排序，以及把提交时间压进分数的编码；
 * 2. 100 万成员排行榜的内存与编码（取榜的吞吐由 verify.sh 用 redis-benchmark 测）；
 * 3. GEOSEARCH 按半径查询；
 * 4. 二倍均值法拆红包，10 万轮模拟（不连 Redis）。
 * 运行：java src/Features.java，连接 127.0.0.1:6379。
 */
public class Features {

    static final class RedisError extends IOException {
        RedisError(String message) {
            super(message);
        }
    }

    /** 最小的 RESP2 客户端：一条连接，一次一条命令。 */
    static final class Resp implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Resp(String host, int port, int timeoutMillis) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        Object call(Object... args) throws IOException {
            send(args);
            return read();
        }

        void send(Object... args) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (Object a : args) {
                String s = String.valueOf(a);
                sb.append('$').append(s.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(s).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        Object read() throws IOException {
            int type = in.read();
            if (type < 0) throw new IOException("连接被关闭");
            String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new RedisError(line);
                case ':' -> Long.parseLong(line);
                case '$' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    byte[] b = in.readNBytes(n + 2);
                    yield new String(b, 0, n, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    List<Object> items = new ArrayList<>();
                    for (int i = 0; i < n; i++) items.add(read());
                    yield items;
                }
                default -> throw new IOException("未知回复类型 " + (char) type);
            };
        }

        void noTimeout() throws IOException {
            socket.setSoTimeout(0);
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != '\r') {
                if (c < 0) throw new IOException("连接被关闭");
                sb.append((char) c);
            }
            in.read();
            return sb.toString();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败不影响实验
            }
        }
    }


    public static void main(String[] args) throws Exception {
        try (Resp r = new Resp("127.0.0.1", 6379, 10_000)) {
            ties(r);
            bigRank(r);
            geo(r);
        }
        redPacket();
    }

    // ---------- 1. 同分排序 ----------

    static final long SHIFT = 100_000_000_000L;                 // 10^11

    static void ties(Resp r) throws IOException {
        r.call("DEL", "rank:raw", "rank:packed");
        r.call("ZADD", "rank:raw", 100, "userA", 100, "userB", 90, "userC");
        out("tie.raw", "ZADD 100 userA、100 userB、90 userC → ZREVRANGE：" + withScores(r.call("ZREVRANGE", "rank:raw", 0, -1, "WITHSCORES")));
        long base = 1_758_182_400_000L;                         // userA 先提交，userB 晚 5 秒
        long[] at = {base, base + 5_000};
        String[] users = {"userA", "userB"};
        for (int i = 0; i < 2; i++) {
            long packed = 100 * SHIFT + (SHIFT - at[i] % SHIFT);
            r.call("ZADD", "rank:packed", packed, users[i]);
        }
        r.call("ZADD", "rank:packed", 90 * SHIFT + (SHIFT - (base + 1_000) % SHIFT), "userC");
        Object score = r.call("ZSCORE", "rank:packed", "userA");
        out("tie.packed", "score = 得分 × 10^11 + (10^11 − 提交时间的后 11 位)，userA 先提交 → ZREVRANGE：%s；userA 的 score / 10^11 取整 = %d".formatted(
                r.call("ZREVRANGE", "rank:packed", 0, -1), (long) (Double.parseDouble((String) score) / SHIFT)));
        double limit = Math.pow(2, 53);
        out("tie.precision", "2^53 = %,.0f，得分上限约 %,.0f".formatted(limit, limit / SHIFT));
    }

    static String withScores(Object reply) {
        List<?> l = (List<?>) reply;
        StringJoiner j = new StringJoiner(", ");
        for (int i = 0; i < l.size(); i += 2) {
            j.add(l.get(i) + " " + l.get(i + 1));
        }
        return j.toString();
    }

    // ---------- 2. 百万成员排行榜 ----------

    static void bigRank(Resp r) throws IOException {
        r.call("DEL", "rank:big");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int batch = 0; batch < 1000; batch++) {
            Object[] cmd = new Object[2 + 2000];
            cmd[0] = "ZADD";
            cmd[1] = "rank:big";
            for (int i = 0; i < 1000; i++) {
                cmd[2 + i * 2] = rnd.nextInt(1_000_000);
                cmd[3 + i * 2] = "user:" + (batch * 1000 + i);
            }
            r.call(cmd);
        }
        out("rank.big", "100 万成员：ZCARD=%s，OBJECT ENCODING=%s，MEMORY USAGE=%.1f MB".formatted(
                r.call("ZCARD", "rank:big"), r.call("OBJECT", "ENCODING", "rank:big"),
                ((Long) r.call("MEMORY", "USAGE", "rank:big", "SAMPLES", 0)) / 1048576.0));
    }

    // ---------- 3. 附近的人 ----------

    static void geo(Resp r) throws IOException {
        r.call("DEL", "shops");
        r.call("GEOADD", "shops", 116.397, 39.909, "店A", 116.405, 39.915, "店B", 116.500, 39.900, "店C");
        for (int km : new int[] {1, 10}) {
            out("geo." + km + "km", "GEOSEARCH FROMLONLAT 116.400 39.910 BYRADIUS %d km ASC WITHDIST → %s".formatted(km,
                    geoList(r.call("GEOSEARCH", "shops", "FROMLONLAT", 116.400, 39.910, "BYRADIUS", km, "km", "ASC", "WITHDIST"))));
        }
        out("geo.type", "TYPE shops = " + r.call("TYPE", "shops"));
    }

    static String geoList(Object reply) {
        StringJoiner j = new StringJoiner(", ");
        for (Object o : (List<?>) reply) {
            List<?> p = (List<?>) o;
            j.add(p.get(0) + " " + p.get(1));
        }
        return j.toString();
    }

    // ---------- 4. 抢红包 ----------

    static List<Long> split(long totalFen, int count) {
        List<Long> out = new ArrayList<>(count);
        long rest = totalFen;
        int restCount = count;
        for (int i = 0; i < count - 1; i++) {
            long max = Math.max(1, rest / restCount * 2);
            long amount = Math.max(1, ThreadLocalRandom.current().nextLong(1, max));
            amount = Math.min(amount, rest - (restCount - 1));
            out.add(amount);
            rest -= amount;
            restCount--;
        }
        out.add(rest);
        return out;
    }

    static void redPacket() {
        int rounds = 100_000;
        long[] sum = new long[10];
        long min = Long.MAX_VALUE;
        long max = 0;
        int broken = 0;
        for (int round = 0; round < rounds; round++) {
            List<Long> l = split(10_000, 10);
            long total = 0;
            for (int i = 0; i < 10; i++) {
                long v = l.get(i);
                total += v;
                sum[i] += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            broken += total == 10_000 ? 0 : 1;
        }
        StringJoiner avg = new StringJoiner("  ");
        for (long s : sum) {
            avg.add("%.2f".formatted(s / (double) rounds / 100));
        }
        out("redpacket", "100 元分给 10 人、模拟 %,d 轮：金额不等于总额的轮次 %d；最小 %.2f 元，最大 %.2f 元；各位置平均：%s".formatted(
                rounds, broken, min / 100.0, max / 100.0, avg));
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
