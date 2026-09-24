import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 故障切换实验的客户端：按 Sentinel 的回答连接 primary，按固定速率写入带序号的 key，记录每一次写入的结果；
 * 另一个线程订阅 Sentinel 的全部事件。
 * 用法：java src/Failover.java <输出前缀> <WAIT 的副本数，0 表示不等> <每秒写入数>；出现 <输出前缀>.stop 时结束。
 * 客户端在 side 网络上：Sentinel 返回 rN-data，客户端改连 rN，所以旧 primary 与复制网络断开后仍能被客户端写入。
 */
public class Failover {

    static final String[] SENTINELS = {"s1", "s2", "s3"};

    /** Redis 返回的错误回复，与连接异常区分开。 */
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

    static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** 依次询问三个 Sentinel，返回客户端可达的 primary 主机名（rN-data 换成 rN）。 */
    static String discover() {
        for (String s : SENTINELS) {
            try (Resp r = new Resp(s, 26379, 500)) {
                Object reply = r.call("SENTINEL", "get-master-addr-by-name", "m");
                if (reply instanceof List<?> l && !l.isEmpty()) return String.valueOf(l.get(0)).replace("-data", "");
            } catch (IOException ignored) {
                // 换下一个 Sentinel
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        String prefix = args[0];
        int waitReplicas = Integer.parseInt(args[1]);
        int rate = Integer.parseInt(args[2]);
        Path stop = Path.of(prefix + ".stop");
        PrintStream writes = new PrintStream(Files.newOutputStream(Path.of(prefix + "-writes.tsv")), true, StandardCharsets.UTF_8);
        PrintStream events = new PrintStream(Files.newOutputStream(Path.of(prefix + "-client.tsv")), true, StandardCharsets.UTF_8);
        PrintStream sentinel = new PrintStream(Files.newOutputStream(Path.of(prefix + "-sentinel.tsv")), true, StandardCharsets.UTF_8);
        writes.println("seq\tstatus\thost\tat_ms\tlatency_us\tdetail");
        events.println("at_ms\tevent\tdetail");
        sentinel.println("at_ms\tchannel\tmessage");

        Thread watcher = Thread.ofPlatform().daemon().start(() -> {
            try (Resp r = new Resp("s1", 26379, 2000)) {
                r.noTimeout();
                r.send("PSUBSCRIBE", "*");
                while (true) {
                    Object m = r.read();
                    if (m instanceof List<?> l && l.size() == 4 && "pmessage".equals(l.get(0))) {
                        sentinel.println(System.currentTimeMillis() + "\t" + l.get(2) + "\t" + clean(String.valueOf(l.get(3))));
                    }
                }
            } catch (IOException e) {
                sentinel.println(System.currentTimeMillis() + "\twatcher-error\t" + clean(e.getMessage()));
            }
        });

        long interval = 1_000_000_000L / rate;
        long start = System.nanoTime();
        Resp conn = null;
        String host = null;
        long lastCheck = 0;
        events.println(System.currentTimeMillis() + "\tworkload_started\trate=" + rate + " wait_replicas=" + waitReplicas);
        for (long seq = 1; !Files.exists(stop); seq++) {
            long due = start + (seq - 1) * interval;
            while (System.nanoTime() < due) Thread.onSpinWait();
            long now = System.currentTimeMillis();
            if (conn == null || now - lastCheck >= 200) {
                lastCheck = now;
                String found = discover();
                if (found != null && !found.equals(host)) {
                    if (host != null) events.println(now + "\tprimary_changed\t" + host + " -> " + found);
                    if (conn != null) conn.close();
                    conn = null;
                    host = found;
                }
                if (conn == null && host != null) {
                    try {
                        conn = new Resp(host, 6379, 1000);
                        events.println(System.currentTimeMillis() + "\tconnected\t" + host);
                    } catch (IOException e) {
                        events.println(System.currentTimeMillis() + "\tconnect_failed\t" + host + " " + clean(e.getMessage()));
                    }
                }
            }
            if (conn == null) {
                writes.println(seq + "\tskipped\t" + host + "\t" + now + "\t0\tno connection");
                continue;
            }
            long t0 = System.nanoTime();
            try {
                conn.call("SET", "f:" + seq, seq);
                String status = "ack";
                String detail = "";
                if (waitReplicas > 0) {
                    long got = (Long) conn.call("WAIT", waitReplicas, 100);
                    detail = "wait=" + got;
                    if (got < waitReplicas) status = "unconfirmed";
                }
                writes.println(seq + "\t" + status + "\t" + host + "\t" + System.currentTimeMillis() + "\t" + (System.nanoTime() - t0) / 1000 + "\t" + detail);
            } catch (RedisError e) {
                writes.println(seq + "\terror\t" + host + "\t" + System.currentTimeMillis() + "\t" + (System.nanoTime() - t0) / 1000 + "\t" + clean(e.getMessage()));
            } catch (IOException e) {
                writes.println(seq + "\tio_error\t" + host + "\t" + System.currentTimeMillis() + "\t" + (System.nanoTime() - t0) / 1000 + "\t" + clean(e.getMessage()));
                events.println(System.currentTimeMillis() + "\tconnection_lost\t" + host + " " + clean(e.getMessage()));
                conn.close();
                conn = null;
            }
        }
        events.println(System.currentTimeMillis() + "\tworkload_stopped\t");
        if (conn != null) conn.close();
        watcher.interrupt();
    }
}
