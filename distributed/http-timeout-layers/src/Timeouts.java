import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/**
 * JDK HttpClient 的 connectTimeout 与请求 timeout 各管一次调用的哪一段。输出为「键<TAB>事实」。
 * 服务端都在同一个 JVM 里：9443 是可控的 HTTPS 服务，9444 接受 TCP 连接但不做 TLS 握手，
 * 9445 的接收队列被占满（Linux 会丢弃新的 SYN），9446 没有监听。需要在 Linux 上运行（容器内）。
 */
public class Timeouts {
    static final Map<String, AtomicInteger> reservations = new ConcurrentHashMap<>();
    static final Map<String, String> results = new ConcurrentHashMap<>();
    static final AtomicInteger sideEffects = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        char[] pass = "example_password".toCharArray();          // 演示值，只用于本地自签证书
        Path ks = Files.createTempDirectory("tls").resolve("server.p12");
        new ProcessBuilder("keytool", "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", new String(pass)).inheritIO().start().waitFor();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ks)) {
            store.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, pass);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        startHttps(ssl);
        startTcpOnly();
        List<Socket> fillers = startFullBacklog();

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .sslContext(ssl)
                .build();
        System.out.println("config\t" + Runtime.version() + "：connectTimeout=1s，请求 timeout=2s，服务端每个阶段卡 5 秒；整次调用的截止时间默认 8 秒（防止测试挂住），body-stalled-with-deadline 设为 3 秒");

        call(client, "refused", "https://127.0.0.1:9446/ok", Duration.ofSeconds(2));
        call(client, "syn-dropped", "https://127.0.0.1:9445/ok", Duration.ofSeconds(2));
        call(client, "tls-stalled", "https://127.0.0.1:9444/ok", Duration.ofSeconds(2));
        call(client, "tls-stalled-no-request-timeout", "https://127.0.0.1:9444/ok", null);
        call(client, "headers-stalled", "https://127.0.0.1:9443/slow-headers", Duration.ofSeconds(2));
        call(client, "headers-stalled-no-request-timeout", "https://127.0.0.1:9443/slow-headers", null);
        call(client, "body-stalled", "https://127.0.0.1:9443/slow-body", Duration.ofSeconds(2));
        call(client, "body-stalled-with-deadline", "https://127.0.0.1:9443/slow-body", Duration.ofSeconds(2), null, 3);

        // 服务端已经执行了副作用，响应在超时之后才返回；客户端等 1.5 秒后重试一次
        for (boolean withKey : new boolean[] {false, true}) {
            sideEffects.set(0);
            String key = withKey ? "signup-42-" + UUID.randomUUID() : null;
            String first = call(client, "commit-then-timeout." + (withKey ? "key" : "nokey") + ".1", "https://127.0.0.1:9443/reserve", Duration.ofSeconds(2), key);
            Thread.sleep(1500);
            String second = call(client, "commit-then-timeout." + (withKey ? "key" : "nokey") + ".2", "https://127.0.0.1:9443/reserve", Duration.ofSeconds(2), key);
            Thread.sleep(2000);
            System.out.printf("reserve.%s\t%s：第一次 %s，重试 %s；服务端实际预留名额 %d 次%n", withKey ? "key" : "nokey",
                    withKey ? "带幂等键" : "不带幂等键", first, second, sideEffects.get());
        }
        fillers.forEach(s -> { try { s.close(); } catch (IOException ignored) { } });
        System.exit(0);
    }

    static String call(HttpClient client, String name, String url, Duration timeout) {
        return call(client, name, url, timeout, null, 8);
    }

    static String call(HttpClient client, String name, String url, Duration timeout, String key) {
        return call(client, name, url, timeout, key, 8);
    }

    /** deadlineSeconds 是整次调用的截止时间：到时取消 sendAsync 返回的 future。 */
    static String call(HttpClient client, String name, String url, Duration timeout, String key, int deadlineSeconds) {
        var b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (timeout != null) b.timeout(timeout);
        if (key != null) b.header("Idempotency-Key", key);
        long t0 = System.nanoTime();
        String result;
        CompletableFuture<HttpResponse<String>> f = client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString());
        try {
            HttpResponse<String> r = f.get(deadlineSeconds, TimeUnit.SECONDS);
            result = "HTTP " + r.statusCode() + " " + r.body().length() + " 字节";
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            result = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : "（" + c.getMessage() + "）");
        } catch (TimeoutException e) {
            f.cancel(true);
            result = deadlineSeconds + " 秒截止时间到，取消调用";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "interrupted";
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%s\t%s，耗时 %d ms%n", name, result, ms);
        return result.replaceAll("（.*）", "");
    }

    // ---------- 服务端 ----------

    static void startHttps(SSLContext ssl) throws IOException {
        SSLServerSocket server = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(9443, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    Thread.ofPlatform().daemon().start(() -> handle(s));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    static void handle(Socket s) {
        try (s) {
            var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String path = requestLine.split(" ")[1];
            String key = null;
            int length = 0;
            for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
                String lower = h.toLowerCase(Locale.ROOT);
                if (lower.startsWith("idempotency-key:")) key = h.substring(16).trim();
                if (lower.startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
            }
            for (int i = 0; i < length; i++) in.read();
            OutputStream out = s.getOutputStream();
            switch (path) {
                case "/slow-headers" -> {
                    Thread.sleep(5000);
                    respond(out, "ok");
                }
                case "/slow-body" -> {
                    String head = "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: text/plain\r\n\r\n";
                    out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    out.write("x".repeat(500).getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    Thread.sleep(5000);                               // 响应头和一半响应体已发出，剩下的一半卡住
                    out.write("x".repeat(500).getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
                case "/reserve" -> {
                    String body;
                    if (key != null && results.containsKey(key)) {
                        body = results.get(key);                      // 同一个幂等键：返回上一次的结果，不再预留
                    } else {
                        sideEffects.incrementAndGet();                // 预留名额（已经提交）
                        body = "reserved-" + sideEffects.get();
                        if (key != null) results.put(key, body);
                        Thread.sleep(3000);                           // 提交之后，响应因为别的原因变慢
                    }
                    respond(out, body);
                }
                default -> respond(out, "ok");
            }
        } catch (Exception ignored) {
            // 客户端超时后会关闭连接，写回响应时出错是预期的
        }
    }

    static void respond(OutputStream out, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    /** 接受 TCP 连接、读取客户端发来的字节，但从不回应：TLS 握手停在 ClientHello 之后。 */
    static void startTcpOnly() throws IOException {
        ServerSocket server = new ServerSocket(9444, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    Thread.ofPlatform().daemon().start(() -> {
                        try (s) {
                            s.getInputStream().transferTo(OutputStream.nullOutputStream());
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    /** backlog 为 1 且从不 accept，先用两个连接占满接收队列；之后 Linux 会丢弃新的 SYN，建连一直得不到回应。 */
    static List<Socket> startFullBacklog() throws IOException {
        new ServerSocket(9445, 1, InetAddress.getLoopbackAddress());
        List<Socket> fillers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", 9445), 1000);
            fillers.add(s);
        }
        return fillers;
    }
}
