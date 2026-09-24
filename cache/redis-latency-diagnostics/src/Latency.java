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
 * 延迟诊断实验的客户端，连接 127.0.0.1:6379。
 *   probe <输出文件>：每毫秒发一次 GET，记录发出时刻（毫秒）与往返耗时（微秒），出现 <输出文件>.stop 时结束
 *   pipeline <输出文件>：每批 1、10、100、1000 条 SET，各发送 100,000 条，记录吞吐与每批往返耗时的分位数
 *   slowsub <秒数>：订阅频道后不读取任何消息，持续若干秒后退出，用来制造输出缓冲区积压
 */
public class Latency {

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
            socket.setTcpNoDelay(true);
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
        switch (args[0]) {
            case "probe" -> probe(Path.of(args[1]));
            case "pipeline" -> pipeline(Path.of(args[1]));
            case "slowsub" -> slowSubscriber(Integer.parseInt(args[1]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    /** 按固定节拍发送。服务器阻塞时，后面的请求只能在客户端排队：latency_us 只含往返，delay_since_due_us 还包含排队，更接近调用方感受到的延迟。 */
    static void probe(Path out) throws Exception {
        Path stop = Path.of(out + ".stop");
        try (Resp r = new Resp("127.0.0.1", 6379, 10_000);
             PrintStream p = new PrintStream(Files.newOutputStream(out), false, StandardCharsets.UTF_8)) {
            r.call("SET", "probe", "x");
            p.println("sent_at_ms\tlatency_us\tdelay_since_due_us");
            long start = System.nanoTime();
            long startWall = System.currentTimeMillis();
            for (long i = 0; !Files.exists(stop) || i % 100 != 0; i++) {
                long due = start + i * 1_000_000L;
                while (System.nanoTime() < due) Thread.onSpinWait();
                long t0 = System.nanoTime();
                r.call("GET", "probe");
                long t1 = System.nanoTime();
                p.println((startWall + (t0 - start) / 1_000_000) + "\t" + (t1 - t0) / 1000 + "\t" + (t1 - due) / 1000);
            }
        }
    }

    static void pipeline(Path out) throws Exception {
        try (Resp r = new Resp("127.0.0.1", 6379, 10_000);
             PrintStream p = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
            p.println("batch\tcommands\tseconds\tops_per_second\tbatch_p50_us\tbatch_p99_us");
            for (int batch : new int[] {1, 10, 100, 1000}) {
                for (int warm = 0; warm < 2; warm++) {
                    int total = 100_000;
                    List<Long> lat = new ArrayList<>();
                    long t0 = System.nanoTime();
                    for (int sent = 0; sent < total; sent += batch) {
                        long b0 = System.nanoTime();
                        for (int i = 0; i < batch; i++) r.send("SET", "pl:" + (sent + i), "v");
                        for (int i = 0; i < batch; i++) r.read();
                        lat.add(System.nanoTime() - b0);
                    }
                    double sec = (System.nanoTime() - t0) / 1e9;
                    if (warm == 0) continue;
                    lat.sort(null);
                    p.printf("%d\t%d\t%.3f\t%.0f\t%d\t%d%n", batch, total, sec, total / sec,
                            lat.get(lat.size() / 2) / 1000, lat.get((int) (lat.size() * 0.99)) / 1000);
                }
            }
        }
    }

    static void slowSubscriber(int seconds) throws Exception {
        try (Resp r = new Resp("127.0.0.1", 6379, 10_000)) {
            r.send("SUBSCRIBE", "news");
            Thread.sleep(seconds * 1000L);
        }
    }
}
