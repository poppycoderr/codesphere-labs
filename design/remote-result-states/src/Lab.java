import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远程调用的结果至少有四种：成功、确实不存在、调用失败、结果未知。
 * 本地起一个 HttpServer 模拟商品服务与订单服务，对比「null/空列表/false」式的客户端与有类型结果的客户端。
 */
public class Lab {

    sealed interface Result<T> permits Found, NotFound, Failed, Unknown {}

    record Found<T>(T value) implements Result<T> {}

    record NotFound<T>() implements Result<T> {}

    record Failed<T>(String reason) implements Result<T> {}

    record Unknown<T>(String reason) implements Result<T> {}

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static volatile String productMode = "ok";
    static final AtomicInteger ordersCreated = new AtomicInteger();
    static final Map<String, String> ordersByKey = new LinkedHashMap<>();
    static final Map<Integer, String> catalog = Map.of(1, "键盘", 2, "鼠标", 3, "显示器");

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        // GET /products/{id}：统一的 200 + {code, data}
        server.createContext("/products/", ex -> {
            int id = Integer.parseInt(ex.getRequestURI().getPath().substring("/products/".length()));
            switch (productMode) {
                case "slow" -> {
                    sleep(500);
                    reply(ex, 200, "{\"code\":\"OK\",\"data\":{\"id\":" + id + ",\"name\":\"" + catalog.get(id) + "\"}}");
                }
                case "busy" -> reply(ex, 200, "{\"code\":\"SYSTEM_BUSY\",\"data\":null}");
                default -> reply(ex, 200, catalog.containsKey(id)
                        ? "{\"code\":\"OK\",\"data\":{\"id\":" + id + ",\"name\":\"" + catalog.get(id) + "\"}}"
                        : "{\"code\":\"OK\",\"data\":null}");
            }
        });
        // GET /products?ids=3,1,2,9 ：IN 查询，按主键顺序返回、缺失的不返回
        server.createContext("/products", ex -> {
            if ("slow".equals(productMode)) {
                sleep(500);
            }
            String q = ex.getRequestURI().getQuery().substring("ids=".length());
            var sb = new StringBuilder("[");
            q.chars();
            List<Integer> ids = new ArrayList<>();
            for (String s : q.split(",")) {
                ids.add(Integer.parseInt(s));
            }
            ids.stream().distinct().sorted().filter(catalog::containsKey).forEach(i ->
                    sb.append(sb.length() > 1 ? "," : "").append("{\"id\":").append(i).append(",\"name\":\"").append(catalog.get(i)).append("\"}"));
            reply(ex, 200, sb.append("]").toString());
        });
        // POST /orders：处理很慢；按幂等键可查询
        server.createContext("/orders", ex -> {
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            if ("GET".equals(ex.getRequestMethod())) {
                String found;
                synchronized (ordersByKey) {
                    found = ordersByKey.get(ex.getRequestURI().getQuery().substring("key=".length()));
                }
                reply(ex, found == null ? 404 : 200, found == null ? "{}" : "{\"order\":\"" + found + "\"}");
                return;
            }
            String existing;
            synchronized (ordersByKey) {
                existing = key == null ? null : ordersByKey.get(key);
                if (existing == null) {
                    existing = "order-" + ordersCreated.incrementAndGet();
                    if (key != null) {
                        ordersByKey.put(key, existing);
                    }
                }
            }
            sleep(500); // 已经落库，响应很慢
            reply(ex, 201, "{\"order\":\"" + existing + "\"}");
        });
        server.start();
        return server;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static HttpClient http = HttpClient.newHttpClient();
    static String base;

    static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(200)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static final Pattern NAME = Pattern.compile("\"name\":\"([^\"]+)\"");
    static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    // ---------- 朴素客户端：成功给值，其他一律 null / 空列表 ----------

    static Optional<String> naiveProduct(int id) {
        try {
            HttpResponse<String> r = get("/products/" + id);
            if (r.statusCode() != 200) {
                return Optional.empty();
            }
            Matcher m = NAME.matcher(r.body());
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static List<String> naiveBatch(List<Integer> ids) {
        try {
            HttpResponse<String> r = get("/products?ids=" + String.join(",", ids.stream().map(String::valueOf).toList()));
            List<String> names = new ArrayList<>();
            Matcher m = NAME.matcher(r.body());
            while (m.find()) {
                names.add(m.group(1));
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------- 有类型的客户端 ----------

    static Result<String> product(int id) {
        try {
            HttpResponse<String> r = get("/products/" + id);
            if (r.statusCode() != 200) {
                return new Failed<>("HTTP " + r.statusCode());
            }
            if (!r.body().contains("\"code\":\"OK\"")) {
                return new Failed<>("业务码 " + r.body().replaceAll(".*\"code\":\"([^\"]+)\".*", "$1"));
            }
            Matcher m = NAME.matcher(r.body());
            return m.find() ? new Found<>(m.group(1)) : new NotFound<>();
        } catch (HttpTimeoutException e) {
            return new Failed<>("超时"); // 查询没有副作用，超时可以当作失败、稍后重试
        } catch (Exception e) {
            return new Failed<>(e.getClass().getSimpleName());
        }
    }

    static Result<Map<Integer, String>> batch(List<Integer> ids) {
        try {
            HttpResponse<String> r = get("/products?ids=" + String.join(",", ids.stream().map(String::valueOf).toList()));
            Map<Integer, String> byId = new LinkedHashMap<>();
            Matcher mi = ID.matcher(r.body());
            Matcher mn = NAME.matcher(r.body());
            while (mi.find() && mn.find()) {
                byId.put(Integer.parseInt(mi.group(1)), mn.group(1));
            }
            return new Found<>(byId);
        } catch (HttpTimeoutException e) {
            return new Failed<>("超时");
        } catch (Exception e) {
            return new Failed<>(e.getClass().getSimpleName());
        }
    }

    static String describe(Result<?> r) {
        return switch (r) {
            case Found<?> f -> "成功：" + f.value();
            case NotFound<?> n -> "确实不存在";
            case Failed<?> f -> "调用失败（" + f.reason() + "）";
            case Unknown<?> u -> "结果未知（" + u.reason() + "）";
        };
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            productMode = "ok";
            out("single.ok", "商品 9 不存在：朴素客户端 " + naiveProduct(9) + "，有类型的客户端 " + describe(product(9)));
            productMode = "busy";
            out("single.busy", "商品服务降级，返回 200 + {code: SYSTEM_BUSY, data: null}：朴素客户端只看 HTTP 状态码 → " + naiveProduct(1)
                    + "（当成不存在），有类型的客户端 → " + describe(product(1)));
            productMode = "slow";
            out("single.timeout", "商品服务超时：朴素客户端 → " + naiveProduct(1) + "，有类型的客户端 → " + describe(product(1)));

            // 清理购物车里已下架的商品
            List<Integer> cart = List.of(1, 2, 3);
            productMode = "slow";
            List<String> alive = naiveBatch(cart);
            long deletedNaive = cart.stream().filter(id -> !alive.contains(catalog.get(id))).count();
            Result<Map<Integer, String>> typed = batch(cart);
            String typedAction = typed instanceof Found<Map<Integer, String>> f
                    ? "删除 " + cart.stream().filter(id -> !f.value().containsKey(id)).count() + " 项"
                    : "跳过这一轮清理（" + describe(typed) + "）";
            out("cleanup", "清理购物车里已下架的商品，商品服务超时：朴素客户端拿到空列表，删除了 " + deletedNaive + " 项（全部）；有类型的客户端 " + typedAction);

            // IN 批量查询：顺序、缺失与重复
            productMode = "ok";
            List<Integer> wanted = List.of(3, 1, 9, 1);
            List<String> names = naiveBatch(wanted);
            var zipped = new StringBuilder();
            for (int i = 0; i < wanted.size(); i++) {
                zipped.append(i == 0 ? "" : "，").append(wanted.get(i)).append("→").append(i < names.size() ? names.get(i) : "（缺）");
            }
            Map<Integer, String> byId = ((Found<Map<Integer, String>>) batch(wanted)).value();
            var mapped = new StringBuilder();
            for (int i = 0; i < wanted.size(); i++) {
                mapped.append(i == 0 ? "" : "，").append(wanted.get(i)).append("→").append(byId.getOrDefault(wanted.get(i), "已下架"));
            }
            out("batch.zip", "按运营配置的顺序 [3, 1, 9, 1] 批量查询，服务端按主键返回且跳过不存在的：按下标对应 " + zipped + "；按 id 对应 " + mapped);

            // 写操作超时：结果未知
            HttpClient h = HttpClient.newHttpClient();
            int before = ordersCreated.get();
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    h.send(HttpRequest.newBuilder(URI.create(base + "/orders")).timeout(Duration.ofMillis(200))
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (HttpTimeoutException e) {
                    // 当成失败，重试
                }
            }
            sleep(700);
            out("write.naive", "下单请求超时被当成失败、直接重试一次：服务端实际创建了 " + (ordersCreated.get() - before) + " 个订单");

            int before2 = ordersCreated.get();
            String key = "cart-77-checkout";
            String outcome = "未知";
            for (int attempt = 1; attempt <= 2 && outcome.equals("未知"); attempt++) {
                try {
                    h.send(HttpRequest.newBuilder(URI.create(base + "/orders")).header("Idempotency-Key", key).timeout(Duration.ofMillis(200))
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                    outcome = "成功";
                } catch (HttpTimeoutException e) {
                    sleep(400);
                    HttpResponse<String> q = h.send(HttpRequest.newBuilder(URI.create(base + "/orders?key=" + key)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    outcome = q.statusCode() == 200 ? "已创建（" + q.body().replaceAll(".*\"order\":\"([^\"]+)\".*", "$1") + "）" : "未知";
                }
            }
            sleep(700);
            out("write.unknown", "超时当作「结果未知」：带幂等键查询后得知 " + outcome + "，服务端共创建 " + (ordersCreated.get() - before2) + " 个订单");
        } finally {
            server.stop(0);
        }
    }
}
