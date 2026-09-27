import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.Security;
import java.time.Duration;
import java.util.*;

/**
 * 在客户端容器里运行，输出为「键<TAB>事实」。参数：场景 [networkaddress.cache.ttl]。
 * 程序自己改写 /dns/hosts（CoreDNS 每秒重新加载），把 api.lab.test 从 backend-1 切到 backend-2。
 * resolve：切换后多久 InetAddress 才返回新地址；negative：先查一个不存在的名字，再加上记录，多久才能查到；
 * pool：HttpClient 每 500ms 请求一次，切换 DNS 后请求落在哪个后端，旧后端开始关闭连接后又怎样。
 */
public class Dns {
    static final Path HOSTS = Path.of("/dns/hosts");
    static final String B1 = "172.29.53.11", B2 = "172.29.53.12";
    static long t0;

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        String ttl = args.length > 1 ? args[1] : null;
        if (ttl != null) Security.setProperty("networkaddress.cache.ttl", ttl);     // 必须在第一次解析之前设置
        switch (scenario) {
            case "resolve" -> resolve(ttl);
            case "negative" -> negative();
            case "pool" -> pool(false);
            case "pool-urlconnection" -> pool(true);
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    static void hosts(String... lines) throws Exception {
        Files.writeString(HOSTS, String.join("\n", lines) + "\n");
    }

    static long now() {
        return System.currentTimeMillis() - t0;
    }

    static String lookup(String name) {
        try {
            return InetAddress.getByName(name).getHostAddress();
        } catch (UnknownHostException e) {
            return "UnknownHost";
        }
    }

    /** 等 CoreDNS 重新加载文件：用 dig 一样的方式直接问 DNS 服务器太麻烦，这里用固定等待。 */
    static void settle() throws InterruptedException {
        Thread.sleep(2500);
    }

    static void resolve(String ttl) throws Exception {
        hosts(B1 + " api.lab.test");
        settle();
        String first = lookup("api.lab.test");
        t0 = System.currentTimeMillis();
        hosts(B2 + " api.lab.test");                                   // t=0 改写记录
        long changedAt = -1;
        while (now() < 45_000) {
            if (lookup("api.lab.test").equals(B2)) {
                changedAt = now();
                break;
            }
            Thread.sleep(200);
        }
        System.out.printf("resolve.%s\tnetworkaddress.cache.ttl=%s，DNS 记录 TTL 5 秒：改写记录前解析到 %s；改写后 %s%n",
                ttl == null ? "default" : ttl, ttl == null ? "未设置（默认）" : ttl, first,
                changedAt < 0 ? "45 秒内一直是旧地址" : changedAt + " ms 后才解析到新地址");
    }

    static void negative() throws Exception {
        hosts(B1 + " api.lab.test");
        settle();
        String miss = lookup("new.lab.test");                          // 记录还不存在
        t0 = System.currentTimeMillis();
        hosts(B1 + " api.lab.test", B2 + " new.lab.test");            // t=0 加上记录
        long foundAt = -1;
        while (now() < 45_000) {
            if (lookup("new.lab.test").equals(B2)) {
                foundAt = now();
                break;
            }
            Thread.sleep(200);
        }
        System.out.printf("negative\t先查一个不存在的名字得到 %s；加上记录后 %s%n", miss,
                foundAt < 0 ? "45 秒内一直查不到" : foundAt + " ms 后才查到");
    }

    /** HttpURLConnection：读完响应体并关闭输入流后，连接回到 JDK 的 keep-alive 缓存里复用。 */
    static String viaUrlConnection(String url) throws Exception {
        var c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        try (var in = c.getInputStream()) {
            return new String(in.readAllBytes());
        }
    }

    static void pool(boolean urlConnection) throws Exception {
        hosts(B1 + " api.lab.test");
        settle();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://api.lab.test:8000/")).timeout(Duration.ofSeconds(2)).build();
        t0 = System.currentTimeMillis();
        Map<String, Integer> before = new TreeMap<>(), afterSwitch = new TreeMap<>(), afterDrain = new TreeMap<>();
        String lastResolved = null;
        boolean switched = false, drained = false;
        long firstNewAfterDrain = -1;
        int errors = 0;
        while (now() < 40_000) {
            if (!switched && now() >= 3_000) {
                hosts(B2 + " api.lab.test");
                switched = true;
                System.out.println((urlConnection ? "pool-urlconnection" : "pool") + ".event\t" + now() + " ms 改写 DNS 记录为 backend-2");
            }
            if (!drained && now() >= 20_000) {
                client.send(HttpRequest.newBuilder(URI.create("http://" + B1 + ":8000/drain")).build(), HttpResponse.BodyHandlers.discarding());
                drained = true;
                System.out.println((urlConnection ? "pool-urlconnection" : "pool") + ".event\t" + now() + " ms 让 backend-1 开始关闭连接（Connection: close）");
            }
            String resolved = lookup("api.lab.test");
            if (!resolved.equals(lastResolved)) {
                System.out.println((urlConnection ? "pool-urlconnection" : "pool") + ".event\t" + now() + " ms JVM 解析 api.lab.test 得到 " + resolved);
                lastResolved = resolved;
            }
            String who;
            try {
                who = urlConnection ? viaUrlConnection("http://api.lab.test:8000/") : client.send(req, HttpResponse.BodyHandlers.ofString()).body();
            } catch (Exception e) {
                who = "error";
                errors++;
            }
            Map<String, Integer> bucket = !switched ? before : !drained ? afterSwitch : afterDrain;
            bucket.merge(who, 1, Integer::sum);
            if (drained && firstNewAfterDrain < 0 && who.equals("backend-2")) firstNewAfterDrain = now();
            Thread.sleep(500);
        }
        String p = urlConnection ? "pool-urlconnection" : "pool";
        System.out.println(p + ".before\t改写 DNS 之前的请求：" + before);
        System.out.println(p + ".switched\t改写 DNS 之后、旧后端关闭连接之前（约 17 秒）：" + afterSwitch);
        System.out.println(p + ".drained\t旧后端开始关闭连接之后：" + afterDrain + "，失败 " + errors + " 次，第一次落到 backend-2 在 " + firstNewAfterDrain + " ms");
    }
}
