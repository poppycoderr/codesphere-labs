import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 持续写入带连续序号的 key，记录客户端最后一次收到 OK 的序号。
 * 用法：java src/Writer.java <输出文件> <每秒写入数>，连接 127.0.0.1:6379；连接出错或出现停止文件 <输出文件>.stop 时结束。
 * 单连接顺序写入：收到第 n 条的 OK 意味着 1..n 都已被服务器确认。
 */
public class Writer {

    /** 最小的 RESP2 客户端：一条连接，一次一条命令，不做线程共享。 */
    static final class Resp implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Resp() throws IOException {
            socket = new Socket("127.0.0.1", 6379);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        Object call(Object... args) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (Object a : args) {
                byte[] b = String.valueOf(a).getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(b.length).append("\r\n").append(String.valueOf(a)).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return read();
        }

        long num(Object... args) throws IOException {
            return (Long) call(args);
        }

        private Object read() throws IOException {
            int type = in.read();
            if (type < 0) throw new IOException("连接被关闭");
            String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new IOException("Redis 错误：" + line);
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
        public void close() throws IOException {
            socket.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Path stop = Path.of(args[0] + ".stop");
        int rate = Integer.parseInt(args[1]);
        long intervalNanos = 1_000_000_000L / rate;
        long lastAcked = 0;
        long lastAckAt = 0;
        String error = "";
        List<Long> latencies = new ArrayList<>();
        long start = System.nanoTime();
        long startWall = System.currentTimeMillis();
        try (Resp r = new Resp()) {
            for (long seq = 1; !Files.exists(stop); seq++) {
                long due = start + (seq - 1) * intervalNanos;
                while (System.nanoTime() < due) Thread.onSpinWait();
                long t0 = System.nanoTime();
                Object reply = r.call("SET", "w:" + seq, seq);
                if (!"OK".equals(reply)) throw new IOException("回复 " + reply);
                latencies.add(System.nanoTime() - t0);
                lastAcked = seq;
                lastAckAt = System.currentTimeMillis();
            }
            error = "stopped";
        } catch (IOException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        latencies.sort(null);
        try (PrintStream p = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
            p.println("started_at_ms\t" + startWall);
            p.println("last_acked_seq\t" + lastAcked);
            p.println("last_ack_at_ms\t" + lastAckAt);
            p.println("rate_per_second\t" + rate);
            p.println("ended_by\t" + error.replace('\t', ' ').replace('\n', ' '));
            if (!latencies.isEmpty()) {
                p.println("latency_p50_us\t" + latencies.get(latencies.size() / 2) / 1000);
                p.println("latency_p99_us\t" + latencies.get((int) (latencies.size() * 0.99)) / 1000);
                p.println("latency_max_us\t" + latencies.get(latencies.size() - 1) / 1000);
            }
        }
    }
}
