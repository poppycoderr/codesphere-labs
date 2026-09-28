import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短链服务的四个契约问题，输出为「键<TAB>事实」：
 * 1. 三种短码生成方式：自增 ID 的可枚举性、随机 7 位 Base62 与 32 位哈希截断在规模上升时的碰撞；
 * 2. 20 个并发请求创建同一个长 URL：「先查再写」与唯一约束；
 * 3. 301、302、307、308 跳转后，JDK HttpClient 与 curl 用什么方法请求目标地址（由 verify.sh 调用 curl）；
 * 4. 目标地址校验：协议白名单、解析后的地址是否为内网或保留地址、userinfo 与数字 IP 写法。
 * 参数：all 运行 1、2、4 并启动跳转服务等待 curl；只连本地 MySQL，不访问外网。
 */
public class ShortUrl {
    static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static void main(String[] args) throws Exception {
        generation();
        sameUrl();
        validation();
        redirects();
    }

    static String base62(long n) {
        StringBuilder s = new StringBuilder();
        do {
            s.append(ALPHABET.charAt((int) (n % 62)));
            n /= 62;
        } while (n > 0);
        return s.reverse().toString();
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }

    // ---------- 1. 生成方式 ----------

    static void generation() throws Exception {
        List<String> seq = new ArrayList<>();
        for (long id = 1_000_000_000L; id < 1_000_000_005L; id++) {
            seq.add(base62(id));
        }
        out("gen.sequential", "自增 ID 转 Base62，连续 5 个：" + seq);
        long space = (long) Math.pow(62, 7);
        for (int n : new int[] {1_000_000, 10_000_000}) {
            long[] codes = new long[n];
            Random r = new Random(n);
            for (int i = 0; i < n; i++) {
                codes[i] = Math.floorMod(r.nextLong(), space);
            }
            out("gen.random7." + n, "随机 7 位 Base62（空间 62^7 = %,d）生成 %,d 个：重复 %d 个，生日界估计 %.1f 个".formatted(
                    space, n, duplicates(codes), (double) n * n / 2 / space));
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int n : new int[] {100_000, 1_000_000, 10_000_000}) {
            long[] h = new long[n];
            for (int i = 0; i < n; i++) {
                byte[] d = sha.digest(("https://example.com/item/" + i).getBytes(StandardCharsets.UTF_8));
                h[i] = ((d[0] & 0xffL) << 24) | ((d[1] & 0xffL) << 16) | ((d[2] & 0xffL) << 8) | (d[3] & 0xffL);
            }
            out("gen.hash32." + n, "SHA-256 截取 32 位，%,d 个不同 URL：碰撞 %,d 个，生日界估计 %,.1f 个".formatted(
                    n, duplicates(h), (double) n * n / 2 / Math.pow(2, 32)));
        }
    }

    static int duplicates(long[] a) {
        long[] b = a.clone();
        Arrays.sort(b);
        int d = 0;
        for (int i = 1; i < b.length; i++) {
            d += b[i] == b[i - 1] ? 1 : 0;
        }
        return d;
    }

    // ---------- 2. 同一个长 URL ----------

    static Connection db() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true", "root", "example_password");
    }

    static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    static String randomCode() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            s.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(62)));
        }
        return s.toString();
    }

    /** 先按 url_hash 查，没有再插入。表上没有唯一约束。 */
    static String checkThenInsert(String url) throws Exception {
        try (Connection c = db()) {
            try (PreparedStatement q = c.prepareStatement("SELECT code FROM short_link_nounique WHERE url_hash = ?")) {
                q.setBytes(1, sha256(url));
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
            String code = randomCode();
            try (PreparedStatement i = c.prepareStatement("INSERT INTO short_link_nounique (code, url_hash, url) VALUES (?, ?, ?)")) {
                i.setString(1, code);
                i.setBytes(2, sha256(url));
                i.setString(3, url);
                i.executeUpdate();
            }
            return code;
        }
    }

    /** 直接插入，唯一约束冲突时读出已有的短码；短码本身冲突时换一个重试。 */
    static String insertOrGet(String url) throws Exception {
        try (Connection c = db()) {
            for (int attempt = 0; attempt < 5; attempt++) {
                try (PreparedStatement i = c.prepareStatement("INSERT INTO short_link (code, url_hash, url) VALUES (?, ?, ?)")) {
                    i.setString(1, randomCode());
                    i.setBytes(2, sha256(url));
                    i.setString(3, url);
                    i.executeUpdate();
                } catch (SQLIntegrityConstraintViolationException e) {
                    if (!e.getMessage().contains("uk_hash")) {
                        continue;                                  // 短码撞了，换一个
                    }
                }
                try (PreparedStatement q = c.prepareStatement("SELECT code FROM short_link WHERE url_hash = ?")) {
                    q.setBytes(1, sha256(url));
                    try (ResultSet rs = q.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                }
            }
            throw new IllegalStateException("重试次数用完");
        }
    }

    interface Create {
        String create(String url) throws Exception;
    }

    static void sameUrl() throws Exception {
        String url = "https://example.com/campaign/2026?utm_source=poster";
        for (var e : Map.<String, Create>of("check_then_insert", ShortUrl::checkThenInsert, "unique_constraint", ShortUrl::insertOrGet).entrySet()) {
            ExecutorService pool = Executors.newFixedThreadPool(20);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return e.getValue().create(url);
                }));
            }
            start.countDown();
            java.util.Set<String> codes = new java.util.TreeSet<>();
            for (Future<String> f : fs) {
                codes.add(f.get());
            }
            pool.shutdown();
            String table = e.getKey().equals("check_then_insert") ? "short_link_nounique" : "short_link";
            int rows;
            try (Connection c = db(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                rows = rs.getInt(1);
            }
            out("same_url." + e.getKey(), "20 个并发请求创建同一个长 URL：表里 %d 行，调用方拿到 %d 个不同的短码".formatted(rows, codes.size()));
        }
    }

    // ---------- 3. 跳转 ----------

    static void redirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18081), 0);
        for (int status : new int[] {301, 302, 307, 308}) {
            server.createContext("/s" + status, ex -> {
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().add("Location", "/target");
                ex.sendResponseHeaders(status, -1);
                ex.close();
            });
        }
        server.createContext("/target", ex -> {
            byte[] body = (ex.getRequestMethod() + " " + ex.getRequestBody().readAllBytes().length).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (int status : new int[] {301, 302, 307, 308}) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18081/s" + status))
                    .POST(HttpRequest.BodyPublishers.ofString("a=1")).build(), HttpResponse.BodyHandlers.ofString());
            out("redirect.jdk." + status, "JDK HttpClient POST 到返回 %d 的短链，目标收到：%s".formatted(status, r.body()));
        }
        for (int status : new int[] {301, 302, 307, 308}) {
            Process p = new ProcessBuilder("curl", "-s", "-L", "-d", "a=1", "http://127.0.0.1:18081/s" + status).start();
            String body = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            out("redirect.curl." + status, "curl -L -d POST 到返回 %d 的短链，目标收到：%s".formatted(status, body));
        }
        server.stop(0);
    }

    // ---------- 4. 目标地址校验 ----------

    /** 演示用的解析器：不查真实 DNS。example.com 解析到公网地址，internal.example 解析到内网地址，localhost 解析到回环地址，其他名字解析失败（校验按失败处理）。 */
    static InetAddress[] resolve(String host) throws Exception {
        return switch (host) {
            case "example.com" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {(byte) 93, (byte) 184, (byte) 215, 14})};
            case "internal.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 1, 2, 3})};
            case "localhost" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})};
            default -> {
                if (host.matches("[0-9.]+") || host.startsWith("[")) {
                    yield new InetAddress[] {InetAddress.getByName(host)};   // IP 字面量，不走 DNS
                }
                throw new java.net.UnknownHostException(host);
            }
        };
    }

    static String check(String target) {
        try {
            URI u = new URI(target);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "拒绝：协议 " + (scheme.isEmpty() ? "缺失" : scheme);
            }
            if (u.getRawUserInfo() != null) {
                return "拒绝：包含 userinfo";
            }
            String host = u.getHost();
            if (host == null) {
                return "拒绝：没有主机名";
            }
            for (InetAddress a : resolve(host)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc)) {
                    return "拒绝：解析到 " + a.getHostAddress();
                }
            }
            return "放行";
        } catch (Exception e) {
            return "拒绝：" + e.getClass().getSimpleName();
        }
    }

    static void validation() {
        String[] cases = {
                "https://example.com/a?b=1",
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "ftp://example.com/file",
                "http://127.0.0.1/admin",
                "http://localhost/admin",
                "http://169.254.169.254/latest/meta-data/",
                "http://[::1]/",
                "http://internal.example/",
                "http://example.com@127.0.0.1/",
                "http://2130706433/",
                "http://0x7f000001/",
        };
        for (String c : cases) {
            out("validate", c + " → " + check(c));
        }
    }
}
