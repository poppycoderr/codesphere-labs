import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Elasticsearch 的几个行为，输出为「键<TAB>事实」。参数 basics：分词、text 与 keyword、路由、近实时刷新、terms 聚合误差、默认线程池；
 * 参数 pool：搜索线程池被重聚合打满。连接 127.0.0.1:9200。
 */
public class EsBehaviors {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();

    record Resp(int status, JsonNode body, String raw) {
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("basics")) {
            analyze();
            textVsKeyword();
            routing();
            refresh();
            aggregation();
            defaultPool();
        } else {
            pool();
        }
    }

    // ---------- 分词 ----------

    static void analyze() throws Exception {
        out("analyze.standard", tokens("standard", "Elasticsearch 9 支持向量检索 in production"));
        out("analyze.english", tokens("english", "Running searches quickly"));
    }

    static String tokens(String analyzer, String text) throws Exception {
        Resp r = call("POST", "/_analyze", Map.of("analyzer", analyzer, "text", text));
        List<String> t = new ArrayList<>();
        r.body().get("tokens").forEach(x -> t.add("\"" + x.get("token").asText() + "\""));
        return analyzer + "：" + text + " → " + t;
    }

    // ---------- text 与 keyword ----------

    static void textVsKeyword() throws Exception {
        call("DELETE", "/tk_demo", null);
        call("PUT", "/tk_demo/_doc/1?refresh=true", Map.of("title", "无线降噪耳机"));
        JsonNode mapping = call("GET", "/tk_demo/_mapping", null).body().at("/tk_demo/mappings/properties/title");
        out("mapping.dynamic", "动态映射的 title：" + JSON.writeValueAsString(mapping));
        out("term.text", "term 查 title（text）「无线降噪耳机」：hits=" + hits(search("tk_demo", "{\"query\":{\"term\":{\"title\":\"无线降噪耳机\"}}}")));
        out("term.keyword", "term 查 title.keyword「无线降噪耳机」：hits=" + hits(search("tk_demo", "{\"query\":{\"term\":{\"title.keyword\":\"无线降噪耳机\"}}}")));
        out("match.text", "match 查 title「降噪」：hits=" + hits(search("tk_demo", "{\"query\":{\"match\":{\"title\":\"降噪\"}}}")));
        Resp agg = call("POST", "/tk_demo/_search?size=0", JSON.readTree("{\"aggs\":{\"t\":{\"terms\":{\"field\":\"title\"}}}}"));
        out("agg.text", "对 title（text）做 terms 聚合：HTTP " + agg.status() + "，" + firstReason(agg));
    }

    // ---------- 路由 ----------

    static void routing() throws Exception {
        call("DELETE", "/route_demo", null);
        call("PUT", "/route_demo", JSON.readTree("{\"settings\":{\"number_of_shards\":3,\"number_of_replicas\":0}}"));
        StringBuilder bulk = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            bulk.append("{\"index\":{\"_id\":\"doc-").append(i).append("\"}}\n{\"user\":\"user-").append(i % 7).append("\"}\n");
        }
        bulk(bulk.toString(), "/route_demo/_bulk?refresh=true");
        out("routing.distribution", "40 个文档（按 _id 路由）在 3 个主分片上的分布：" + shardDocs("route_demo"));
        bulk = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            bulk.append("{\"index\":{\"_id\":\"r-").append(i).append("\",\"routing\":\"user-42\"}}\n{\"user\":\"user-42\"}\n");
        }
        bulk(bulk.toString(), "/route_demo/_bulk?refresh=true");
        Resp r = call("GET", "/route_demo/_search?routing=user-42&size=100", null);
        int own = 0;
        for (JsonNode h : r.body().at("/hits/hits")) {
            own += h.at("/_source/user").asText().equals("user-42") ? 1 : 0;
        }
        out("routing.search", "?routing=user-42 搜索：_shards.total=%d，返回 %d 条，其中 user-42 的 %d 条".formatted(
                r.body().at("/_shards/total").asInt(), r.body().at("/hits/total/value").asInt(), own));
    }

    static String shardDocs(String index) throws Exception {
        Resp r = call("GET", "/_cat/shards/" + index + "?format=json&h=shard,prirep,docs", null);
        Map<Integer, Integer> m = new java.util.TreeMap<>();
        for (JsonNode s : r.body()) {
            m.put(s.get("shard").asInt(), s.get("docs").asInt());
        }
        return m.toString();
    }

    // ---------- 近实时 ----------

    static void refresh() throws Exception {
        call("DELETE", "/nrt_demo", null);
        call("PUT", "/nrt_demo", JSON.readTree("{\"settings\":{\"refresh_interval\":\"30s\",\"number_of_replicas\":0}}"));
        Resp w = call("PUT", "/nrt_demo/_doc/1", Map.of("msg", "hello"));
        int before = hits(search("nrt_demo", "{\"query\":{\"match_all\":{}}}"));
        boolean found = call("GET", "/nrt_demo/_doc/1", null).body().get("found").asBoolean();
        call("POST", "/nrt_demo/_refresh", null);
        int after = hits(search("nrt_demo", "{\"query\":{\"match_all\":{}}}"));
        out("nrt.interval30s", "refresh_interval=30s：写入 result=%s；立即搜索 hits=%d；按 ID GET found=%s；POST _refresh 后搜索 hits=%d".formatted(
                w.body().get("result").asText(), before, found, after));
        long s = System.nanoTime();
        Resp wf = call("PUT", "/nrt_demo/_doc/2?refresh=wait_for", Map.of("msg", "world"));
        long waitMs = (System.nanoTime() - s) / 1_000_000;
        int now = hits(search("nrt_demo", "{\"query\":{\"match_all\":{}}}"));
        out("nrt.wait_for", "?refresh=wait_for 写入耗时 %d ms，响应含 forced_refresh=%s，紧接着搜索 hits=%d".formatted(
                waitMs, wf.body().has("forced_refresh"), now));
        Resp ft = call("PUT", "/nrt_demo/_doc/3?refresh=true", Map.of("msg", "now"));
        out("nrt.true", "?refresh=true 写入：响应 forced_refresh=%s".formatted(ft.body().path("forced_refresh").asBoolean(false)));
    }

    // ---------- terms 聚合误差 ----------

    /** 3 个热门品牌各 3000、2500、2000 条，40 个长尾品牌各 300—420 条（固定种子），打乱后以固定 _id 写入 5 分片与 1 分片两个索引，文档在分片间的分布可以复现。 */
    static Map<String, Integer> loadAggData() throws Exception {
        Random r = new Random(20260928);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("brand-hot-A", 3000);
        counts.put("brand-hot-B", 2500);
        counts.put("brand-hot-C", 2000);
        for (int i = 1; i <= 40; i++) {
            counts.put("brand-long-%02d".formatted(i), 300 + r.nextInt(121));
        }
        List<String> docs = new ArrayList<>();
        counts.forEach((b, n) -> {
            for (int i = 0; i < n; i++) {
                docs.add(b);
            }
        });
        Collections.shuffle(docs, r);
        for (String idx : new String[] {"agg_demo", "agg_single"}) {
            call("DELETE", "/" + idx, null);
            int shards = idx.equals("agg_demo") ? 5 : 1;
            call("PUT", "/" + idx, JSON.readTree("{\"settings\":{\"number_of_shards\":" + shards + ",\"number_of_replicas\":0}}"));
            for (int from = 0; from < docs.size(); from += 5000) {
                StringBuilder b = new StringBuilder();
                for (int i = from; i < Math.min(docs.size(), from + 5000); i++) {
                    b.append("{\"index\":{\"_id\":\"d-").append(i).append("\"}}\n{\"brand\":\"").append(docs.get(i)).append("\",\"sku\":\"sku-").append(i % 97).append("\"}\n");
                }
                bulk(b.toString(), "/" + idx + "/_bulk");
            }
            call("POST", "/" + idx + "/_refresh", null);
        }
        return counts;
    }

    static void aggregation() throws Exception {
        Map<String, Integer> counts = loadAggData();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<String, Integer>> truth = new ArrayList<>(counts.entrySet());
        truth.sort((a, b) -> b.getValue() - a.getValue());
        out("agg.truth", "%d 个文档；真实前 5：%s".formatted(total, truth.subList(0, 5)));
        out("agg.default", termsTop("agg_demo", null));
        out("agg.shard_size_1000", termsTop("agg_demo", 1000));
        out("agg.single_shard", termsTop("agg_single", null));
    }

    static String termsTop(String index, Integer shardSize) throws Exception {
        String ss = shardSize == null ? "" : ",\"shard_size\":" + shardSize;
        Resp r = call("POST", "/" + index + "/_search?size=0", JSON.readTree(
                "{\"aggs\":{\"brands\":{\"terms\":{\"field\":\"brand.keyword\",\"size\":5,\"show_term_doc_count_error\":true" + ss + "}}}}"));
        JsonNode a = r.body().at("/aggregations/brands");
        StringJoiner j = new StringJoiner(", ", "[", "]");
        int maxBucketErr = 0;
        for (JsonNode b : a.get("buckets")) {
            j.add(b.get("key").asText() + "=" + b.get("doc_count").asInt());
            maxBucketErr = Math.max(maxBucketErr, b.get("doc_count_error_upper_bound").asInt());
        }
        return "%s（%s）：前 5 %s；doc_count_error_upper_bound=%d，sum_other_doc_count=%d，各桶误差上界最大=%d".formatted(
                index, shardSize == null ? "默认 shard_size" : "shard_size=" + shardSize, j,
                a.get("doc_count_error_upper_bound").asInt(), a.get("sum_other_doc_count").asInt(), maxBucketErr);
    }

    // ---------- 线程池 ----------

    static void defaultPool() throws Exception {
        out("pool.default", "默认 search 线程池（容器 2 CPU）：" + poolLine());
    }

    static String poolLine() throws Exception {
        JsonNode p = call("GET", "/_cat/thread_pool/search?format=json&h=name,active,queue,rejected,completed,size,queue_size", null).body().get(0);
        return "size=%s，queue_size=%s，active=%s，queue=%s，rejected=%s，completed=%s".formatted(
                p.get("size").asText(), p.get("queue_size").asText(), p.get("active").asText(), p.get("queue").asText(),
                p.get("rejected").asText(), p.get("completed").asText());
    }

    /** 60 个并发、共 120 个请求打一个嵌套 terms 聚合（5 分片索引），统计 HTTP 状态与线程池的 rejected 增量。 */
    static void pool() throws Exception {
        String heavy = "{\"size\":0,\"aggs\":{\"b\":{\"terms\":{\"field\":\"brand.keyword\",\"size\":50},"
                + "\"aggs\":{\"s\":{\"terms\":{\"field\":\"sku.keyword\",\"size\":100}}}}}}";
        loadAggData();
        out("pool.before", "调小后的 search 线程池：" + poolLine());
        long rejectedBefore = Long.parseLong(call("GET", "/_cat/thread_pool/search?format=json&h=rejected", null).body().get(0).get("rejected").asText());
        Map<Integer, AtomicInteger> status = new ConcurrentHashMap<>();
        List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        ExecutorService ex = Executors.newFixedThreadPool(60);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            fs.add(ex.submit(() -> {
                start.await();
                Resp r = raw("POST", "/agg_demo/_search", heavy);
                status.computeIfAbsent(r.status(), k -> new AtomicInteger()).incrementAndGet();
                if (r.status() == 429 && reasons.isEmpty()) {
                    reasons.add(firstReason(r));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        ex.shutdown();
        long rejectedAfter = Long.parseLong(call("GET", "/_cat/thread_pool/search?format=json&h=rejected", null).body().get(0).get("rejected").asText());
        out("pool.status", "60 个并发、共 120 个嵌套聚合请求：各 HTTP 状态的次数 " + new java.util.TreeMap<>(status));
        out("pool.rejected", "search 线程池 rejected 增加 %d".formatted(rejectedAfter - rejectedBefore));
        out("pool.reason", "第一个 429 的原因：" + (reasons.isEmpty() ? "无" : reasons.get(0)));
    }

    // ---------- HTTP ----------

    static Resp call(String method, String path, Object body) throws Exception {
        return raw(method, path, body == null ? null : JSON.writeValueAsString(body));
    }

    static Resp raw(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:9200" + path)).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node;
        try {
            node = JSON.readTree(r.body());
        } catch (Exception e) {
            node = JSON.nullNode();
        }
        return new Resp(r.statusCode(), node, r.body());
    }

    static void bulk(String ndjson, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:9200" + path))
                .header("Content-Type", "application/x-ndjson").POST(HttpRequest.BodyPublishers.ofString(ndjson)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200 || JSON.readTree(r.body()).get("errors").asBoolean()) {
            throw new IllegalStateException("bulk 失败：" + r.body().substring(0, Math.min(300, r.body().length())));
        }
    }

    static Resp search(String index, String query) throws Exception {
        return raw("POST", "/" + index + "/_search", query);
    }

    static int hits(Resp r) {
        return r.body().at("/hits/total/value").asInt();
    }

    static String firstReason(Resp r) {
        JsonNode c = r.body().at("/error/root_cause/0");
        String reason = c.path("reason").asText();
        reason = reason.replaceAll("TimedRunnable\\{.*?\\}", "TimedRunnable{...}").replaceAll("\\[name = [^/]*/", "[name = .../");
        return c.path("type").asText() + "：" + (reason.length() > 220 ? reason.substring(0, 220) + "…" : reason);
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
