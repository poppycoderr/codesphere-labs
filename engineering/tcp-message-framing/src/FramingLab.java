import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP 是字节流：发送方的一次写与接收方的一次读没有对应关系。
 * 各场景在本机回环地址上用固定的先后顺序（必要处加等待）构造，使结果确定。
 */
public class FramingLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String s(byte[] buf, int n) { return new String(buf, 0, n, StandardCharsets.UTF_8); }

    interface ServerSide { void serve(Socket s) throws Exception; }
    interface ClientSide { void run(Socket s) throws Exception; }

    /** 建一条连接，服务端逻辑在另一个线程里运行 */
    static void connection(ServerSide server, ClientSide client) throws Exception {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread t = Thread.ofPlatform().start(() -> {
                try (Socket s = ss.accept()) { server.serve(s); }
                catch (java.io.IOException ignored) { }               // 客户端先关闭连接时，服务端的读写会失败，属于预期
                catch (Exception e) { System.out.println("server\t" + e); }
            });
            try (Socket c = new Socket("127.0.0.1", ss.getLocalPort())) { c.setTcpNoDelay(true); client.run(c); }
            t.join();
        }
    }

    static void writeFrame(DataOutputStream o, byte[] payload) throws Exception { o.writeInt(payload.length); o.write(payload); o.flush(); }
    static byte[] readFrame(DataInputStream in, int maxLength) throws Exception {
        int n = in.readInt();
        if (n < 0 || n > maxLength) throw new IllegalStateException("frame length " + n + " exceeds limit " + maxLength);
        byte[] p = new byte[n];
        in.readFully(p);
        return p;
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、三次写，一次读
        String[] got = new String[1];
        connection(s -> {
            Thread.sleep(300);                                   // 等发送方三次写都到了再读
            byte[] buf = new byte[1024];
            int n = s.getInputStream().read(buf);
            got[0] = n + " 字节：" + s(buf, n);
        }, c -> {
            OutputStream o = c.getOutputStream();
            for (String m : new String[]{"{\"id\":1}", "{\"id\":2}", "{\"id\":3}"}) { o.write(b(m)); o.flush(); }
            Thread.sleep(500);
        });
        out("merge", "发送方分三次写出三条消息；接收方稍后的一次 read 得到 " + got[0]);

        // 二、一次逻辑消息分两段到达，一次读只拿到前一段
        connection(s -> {
            byte[] buf = new byte[1024];
            int n = s.getInputStream().read(buf);
            got[0] = n + " 字节：" + s(buf, n);
            while (s.getInputStream().read(buf) > 0) { }
        }, c -> {
            OutputStream o = c.getOutputStream();
            byte[] msg = b("{\"orderId\":\"A-1001\",\"amount\":1999}");
            o.write(msg, 0, 20); o.flush();
            Thread.sleep(300);                                   // 后半段晚到
            o.write(msg, 20, msg.length - 20); o.flush();
        });
        out("split", "一条 34 字节的消息分两段到达；接收方的第一次 read 得到 " + got[0]);

        // 三、长度前缀：同样的两种到达方式
        List<String> frames = new ArrayList<>();
        connection(s -> {
            DataInputStream in = new DataInputStream(s.getInputStream());
            Thread.sleep(300);
            for (int i = 0; i < 4; i++) frames.add(new String(readFrame(in, 1 << 20), StandardCharsets.UTF_8));
        }, c -> {
            DataOutputStream o = new DataOutputStream(c.getOutputStream());
            for (String m : new String[]{"{\"id\":1}", "{\"id\":2}", "{\"id\":3}"}) writeFrame(o, b(m));
            byte[] msg = b("{\"orderId\":\"A-1001\",\"amount\":1999}");
            o.writeInt(msg.length); o.write(msg, 0, 20); o.flush();
            Thread.sleep(300);
            o.write(msg, 20, msg.length - 20); o.flush();
        });
        out("length_prefix", "4 字节长度加内容，readFully 读满：收到 " + frames.size() + " 条：" + frames);

        // 四、分隔符：内容里出现分隔符
        List<String> lines = new ArrayList<>();
        connection(s -> {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) lines.add(line);
        }, c -> {
            OutputStream o = c.getOutputStream();
            o.write(b("{\"note\":\"first\"}\n")); o.write(b("{\"note\":\"line one\nline two\"}\n")); o.flush();
        });
        out("delimiter", "换行分隔，发出 2 条消息（第 2 条的内容里有换行）：接收方读到 " + lines.size() + " 行：" + String.join(" ‖ ", lines));

        // 五、长度字段没有上限：把别的协议的前 4 个字节当成长度
        out("length.http", "把 \"GET \" 这 4 个字节当作长度 = " + ByteBuffer.wrap(b("GET ")).getInt());
        out("length.tls", "把 TLS 握手记录的前 4 个字节 16 03 01 02 当作长度 = " + ByteBuffer.wrap(new byte[]{0x16, 0x03, 0x01, 0x02}).getInt());
        String[] limited = new String[1];
        connection(s -> {
            try { readFrame(new DataInputStream(s.getInputStream()), 1 << 20); limited[0] = "读到一帧"; }
            catch (IllegalStateException e) { limited[0] = "拒绝并关闭连接（" + e.getMessage() + "）"; }
        }, c -> { c.getOutputStream().write(b("GET / HTTP/1.1\r\nHost: example\r\n\r\n")); c.getOutputStream().flush(); Thread.sleep(200); });
        out("length.limit", "上限 1 MB 的接收方收到一个 HTTP 请求：" + limited[0]);

        // 六、同一条连接上的两个请求，响应不按请求顺序回来
        ServerSide outOfOrder = s -> {
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream o = new DataOutputStream(s.getOutputStream());
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (int i = 0; i < 2; i++) {
                    String req = new String(readFrame(in, 1 << 20), StandardCharsets.UTF_8);   // 形如 "7|slow" 或 "8|fast"
                    pool.submit(() -> {
                        try {
                            String[] p = req.split("\\|");
                            if (p[1].equals("slow")) Thread.sleep(400);
                            synchronized (o) { writeFrame(o, b(p[0] + "|result-of-" + p[1])); }
                        } catch (Exception ignored) { }
                        return null;
                    });
                }
            }
        };
        String[] byOrder = new String[2];
        Map<String, String> byId = new ConcurrentHashMap<>();
        connection(outOfOrder, c -> {
            DataOutputStream o = new DataOutputStream(c.getOutputStream());
            DataInputStream in = new DataInputStream(c.getInputStream());
            writeFrame(o, b("7|slow")); writeFrame(o, b("8|fast"));
            for (int i = 0; i < 2; i++) {
                String[] p = new String(readFrame(in, 1 << 20), StandardCharsets.UTF_8).split("\\|");
                byOrder[i] = p[1];
                byId.put(p[0], p[1]);
            }
        });
        out("correlation.by_order", "先发慢请求再发快请求，按到达顺序配对：慢请求拿到 " + byOrder[0] + "，快请求拿到 " + byOrder[1]);
        out("correlation.by_id", "按帧里的请求编号配对：请求 7（慢）拿到 " + byId.get("7") + "，请求 8（快）拿到 " + byId.get("8"));

        // 七、超时之后继续复用这条连接
        String[] reused = new String[2];
        connection(s -> {
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream o = new DataOutputStream(s.getOutputStream());
            for (int i = 0; i < 2; i++) {
                String req = new String(readFrame(in, 1 << 20), StandardCharsets.UTF_8);
                if (req.equals("balance-of-alice")) Thread.sleep(500);              // 第一个请求处理得慢
                writeFrame(o, b("answer-to-" + req));
            }
        }, c -> {
            DataOutputStream o = new DataOutputStream(c.getOutputStream());
            DataInputStream in = new DataInputStream(c.getInputStream());
            c.setSoTimeout(200);
            writeFrame(o, b("balance-of-alice"));
            try { reused[0] = new String(readFrame(in, 1 << 20), StandardCharsets.UTF_8); } catch (SocketTimeoutException e) { reused[0] = "读超时"; }
            c.setSoTimeout(2000);
            writeFrame(o, b("balance-of-bob"));
            reused[1] = new String(readFrame(in, 1 << 20), StandardCharsets.UTF_8);
        });
        out("reuse_after_timeout", "第一个请求（查 alice）" + reused[0] + "；在同一条连接上接着发第二个请求（查 bob），读到的响应是 " + reused[1]);

        // 八、非阻塞写：一次 write 没有写完
        CountDownLatch done = new CountDownLatch(1);
        long[] w = new long[2];
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread t = Thread.ofPlatform().start(() -> { try (Socket s = ss.accept()) { done.await(); } catch (Exception ignored) { } });   // 接收方一直不读
            try (SocketChannel ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", ss.getLocalPort()))) {
                ch.configureBlocking(false);
                ByteBuffer big = ByteBuffer.allocate(64 * 1024 * 1024);
                w[0] = ch.write(big);
                w[1] = big.remaining();
            }
            done.countDown(); t.join();
        }
        out("partial_write", "非阻塞通道一次 write 64 MB，对端不读：返回值小于 64 MB = " + (w[0] < 64L * 1024 * 1024) + "，缓冲区里还有数据没写出 = " + (w[1] > 0));
    }
}
