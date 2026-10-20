import com.sun.net.httpserver.HttpServer;
import java.lang.annotation.*;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** 声明式 HTTP 客户端：接口方法怎样变成请求；路径与查询参数的编码、错误状态、超时，以及手写代理的两个编码错误 */
public class Lab {
    public interface UserClient {
        @GetExchange("/users/{id}") String get(@PathVariable("id") String id);
        /** 不写参数名：依赖编译时的 -parameters */
        @GetExchange("/users/{id}") String getNoName(@PathVariable String id);
        @GetExchange("/search") String search(@RequestParam("q") String q, @RequestParam("tag") List<String> tags);
        @GetExchange("/missing") String missing();
        @GetExchange("/missing") ResponseEntity<String> missingEntity();
        @GetExchange("/boom") String boom();
        @GetExchange("/slow") String slow();
        @PostExchange("/orders") String create(@RequestBody String body);
        /** 接口里的默认方法：代理不拦截，直接执行，里面的两次调用各发一个请求 */
        default String twice(String id) { return get(id) + " | " + get(id); }
    }

    /** 手写的最小声明式客户端用的注解 */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @interface Get { String value(); }
    interface HandClient { @Get("/users/{id}") String get(String id); }

    static HandClient handProxy(String base, boolean formEncode) {
        HttpClient http = HttpClient.newHttpClient();
        return (HandClient) Proxy.newProxyInstance(Lab.class.getClassLoader(), new Class<?>[]{HandClient.class}, (proxy, m, args) -> {
            String id = (String) args[0];
            String value = formEncode ? URLEncoder.encode(id, StandardCharsets.UTF_8) : id;
            String path = m.getAnnotation(Get.class).value().replace("{id}", value);
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString()).body();
        });
    }

    static final AtomicInteger requests = new AtomicInteger();
    static void out(String k, String v) { System.out.println(k + "\t" + v.replaceAll("127\\.0\\.0\\.1:\\d+", "127.0.0.1:<port>")); }
    static String attempt(java.util.function.Supplier<String> s) {
        try { return s.get(); } catch (RuntimeException e) { return e.getClass().getName().replace("org.springframework.web.client.", "") + "：" + e.getMessage(); }
    }

    public static void main(String[] args) throws Exception {
        try { run(); } catch (Throwable t) { Throwable x = t; StringBuilder sb = new StringBuilder(); while (x != null) { sb.append(x).append(" <- "); x = x.getCause(); } out("crash", sb.toString()); } finally { System.exit(0); }
    }

    static void run() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            String p = ex.getRequestURI().getPath();
            int status = p.equals("/missing") ? 404 : p.equals("/boom") ? 500 : 200;
            if (p.equals("/slow")) try { Thread.sleep(3000); } catch (InterruptedException e) { }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String text = status == 200
                    ? ex.getRequestMethod() + " rawPath=" + ex.getRequestURI().getRawPath() + " path=" + p
                      + (ex.getRequestURI().getRawQuery() == null ? "" : " rawQuery=" + ex.getRequestURI().getRawQuery()) + (body.isEmpty() ? "" : " body=" + body)
                    : "error " + status;
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(status, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        out("env", "spring=" + org.springframework.core.SpringVersion.getVersion() + " java=" + Runtime.version());

        RestClient rc = RestClient.builder().baseUrl(base).build();
        UserClient c = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(UserClient.class);
        out("proxy", "createClient 返回的对象是 JDK 动态代理：" + Proxy.isProxyClass(c.getClass()));
        out("param.name", "@PathVariable 不写名字（单文件启动没有 -parameters）：" + attempt(() -> c.getNoName("1")));
        out("path.encode", "get(\"a/b c\")：" + c.get("a/b c"));
        out("query.encode", "search(\"x&y+z\", [\"a\",\"b\"])：" + c.search("x&y+z", List.of("a", "b")));
        out("post", "create(\"{\\\"sku\\\":1}\")：" + c.create("{\"sku\":1}"));
        int before = requests.get();
        out("default", "调用接口默认方法 twice(\"7\")：" + c.twice("7") + "；服务端收到 " + (requests.get() - before) + " 个请求");
        out("status.404", "返回类型 String，服务端 404：" + attempt(c::missing));
        out("status.404.entity", "返回类型 ResponseEntity<String>，服务端 404：" + attempt(() -> c.missingEntity().getStatusCode().toString()));
        out("status.500", "服务端 500：" + attempt(c::boom));

        long t0 = System.nanoTime();
        String r = attempt(c::slow);
        out("timeout.default", String.format("默认请求工厂，服务端 3 秒后才响应：等了约 %d 秒，结果「%s」", Math.round((System.nanoTime() - t0) / 1e9), r));
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory();
        f.setReadTimeout(Duration.ofSeconds(1));
        UserClient timed = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(RestClient.builder().baseUrl(base).requestFactory(f).build())).build().createClient(UserClient.class);
        t0 = System.nanoTime();
        r = attempt(timed::slow);
        out("timeout.read", String.format("读取超时设为 1 秒：约 %d 秒后「%s」", Math.round((System.nanoTime() - t0) / 1e9), r.length() > 90 ? r.substring(0, 90) + "…" : r));

        out("hand.raw", "手写代理直接替换 {id}，get(\"a/b\")：" + handProxy(base, false).get("a/b"));
        out("hand.space", "手写代理直接替换 {id}，get(\"a b\")：" + attempt(() -> { try { return handProxy(base, false).get("a b"); } catch (RuntimeException e) { throw e; } }));
        out("hand.urlencoder", "手写代理用 URLEncoder 编码路径，get(\"a b\")：" + handProxy(base, true).get("a b"));
        server.stop(0);
    }
}
