import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 同一组目标分别用两种方式访问，输出为「键<TAB>事实」：
 * - JDK HttpClient，连接超时 2 秒、请求超时 5 秒：业务代码通常看到的异常；
 * - 普通 Socket.connect，超时 10 秒（域名先用 InetAddress 解析）：更接近系统调用返回的错误。
 * 每一项都记录拿到的是状态码还是异常、异常链与耗时。
 */
public class Client {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String[][] cases = {
                {"ok", "172.31.7.10", "8080", "/ok"},
                {"http_503", "172.31.7.10", "8080", "/fail"},
                {"dns_nxdomain", "no-such-service.invalid", "8080", "/ok"},
                {"route_unreachable", "10.201.0.5", "8080", "/ok"},
                {"neighbor_failed", "172.31.7.99", "8080", "/ok", "172.31.7.98"},
                {"port_closed", "172.31.7.10", "9090", "/ok"},
                {"syn_dropped", "172.31.7.10", "8081", "/ok"},
        };
        for (String[] c : cases) {
            String url = "http://" + c[1] + ":" + c[2] + c[3];
            long s = System.nanoTime();
            String result;
            try {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                result = "HTTP " + r.statusCode();
            } catch (Exception e) {
                result = chain(e);
            }
            System.out.printf("%s.http\t%s → %s，耗时 %s%n", c[0], url, result, elapsed(s));

            s = System.nanoTime();
            String host = c.length > 4 ? c[4] : c[1];                  // 邻居解析失败换一个没访问过的地址，避免邻居表里已有 FAILED 记录
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(InetAddress.getByName(host), Integer.parseInt(c[2])), 10_000);
                result = "连接成功";
            } catch (Exception e) {
                result = chain(e);
            }
            System.out.printf("%s.socket\t%s:%s → %s，耗时 %s%n", c[0], host, c[2], result, elapsed(s));
        }
    }

    static String chain(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (b.length() > 0) {
                b.append(" ← ");
            }
            b.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                b.append("(").append(t.getMessage()).append(")");
            }
        }
        return b.toString();
    }

    static String elapsed(long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        return ms < 50 ? "< 50 ms" : "约 " + Math.round(ms / 100.0) / 10.0 + " 秒";
    }
}
