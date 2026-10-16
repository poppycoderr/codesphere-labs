import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 把领域模型暴露成 HTTP 接口时的几个契约：整体替换与丢失更新、If-Match 条件更新、缺少前置条件时 428、
 * PUT 缺字段被当成清空、用状态字段还是用命令端点改状态、POST 重试与幂等键、耗时操作的 202 受理。
 * Spring MVC 7.0.9 的 MockMvc 在进程内执行，请求的先后顺序由代码固定。
 */
public class ApiLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    /** 领域对象：地址、备注、状态；状态只能按 NEW → PAID → SHIPPED，取消只能在发货前 */
    static final class Order {
        String address, note, status = "NEW"; int version = 1;
        void cancel() { if (status.equals("SHIPPED")) throw new IllegalStateException("已发货不能取消"); status = "CANCELLED"; version++; }
        String json() { return "{\"address\":" + q(address) + ",\"note\":" + q(note) + ",\"status\":\"" + status + "\"}"; }
        static String q(String s) { return s == null ? "null" : "\"" + s + "\""; }
    }
    record OrderBody(String address, String note, String status) { }
    static final Map<String, Order> ORDERS = new ConcurrentHashMap<>();
    static final Map<String, String> IDEMPOTENCY = new ConcurrentHashMap<>();
    static final Map<String, String> JOBS = new HashMap<>();
    static int seq = 0;

    @RestController
    public static class Api {
        @GetMapping("/orders/{id}") ResponseEntity<String> read(@PathVariable("id") String id) {
            Order o = ORDERS.get(id);
            return ResponseEntity.ok().eTag("\"" + o.version + "\"").body(o.json());
        }
        /** v1：PUT 整体替换，不检查版本 */
        @PutMapping("/v1/orders/{id}") String replace(@PathVariable("id") String id, @RequestBody OrderBody b) {
            Order o = ORDERS.get(id); o.address = b.address(); o.note = b.note(); if (b.status() != null) o.status = b.status(); o.version++; return o.json();
        }
        /** v2：PUT 必须带 If-Match，版本不符返回 412，缺少返回 428 */
        @PutMapping("/v2/orders/{id}") ResponseEntity<String> replaceIf(@PathVariable("id") String id, @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody OrderBody b) {
            Order o = ORDERS.get(id);
            if (ifMatch == null) return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body("缺少 If-Match");
            synchronized (o) {
                if (!ifMatch.equals("\"" + o.version + "\"")) return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body("版本已变为 " + o.version);
                o.address = b.address(); o.note = b.note(); o.version++;
            }
            return ResponseEntity.ok().eTag("\"" + o.version + "\"").body(o.json());
        }
        /** PATCH：只改请求里出现的字段（JSON Merge Patch 的简化） */
        @PatchMapping("/orders/{id}") String patchIt(@PathVariable("id") String id, @RequestBody Map<String, String> b) {
            Order o = ORDERS.get(id); if (b.containsKey("address")) o.address = b.get("address"); if (b.containsKey("note")) o.note = b.get("note"); o.version++; return o.json();
        }
        /** 命令端点：取消经过领域规则 */
        @PostMapping("/orders/{id}/cancellation") ResponseEntity<String> cancel(@PathVariable("id") String id) {
            try { ORDERS.get(id).cancel(); return ResponseEntity.ok(ORDERS.get(id).json()); }
            catch (IllegalStateException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage()); }
        }
        /** 创建：可选的 Idempotency-Key */
        @PostMapping("/orders") ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody OrderBody b) {
            if (key != null && IDEMPOTENCY.containsKey(key)) return ResponseEntity.status(HttpStatus.CREATED).header("Location", "/orders/" + IDEMPOTENCY.get(key)).body("replayed");
            String id = "o-" + (++seq); Order o = new Order(); o.address = b.address(); ORDERS.put(id, o);
            if (key != null) IDEMPOTENCY.put(key, id);
            return ResponseEntity.status(HttpStatus.CREATED).header("Location", "/orders/" + id).body(id);
        }
        /** 耗时操作：受理后返回 202 和状态资源 */
        @PostMapping("/reports") ResponseEntity<String> report() {
            String job = "j-1"; JOBS.put(job, "RUNNING");
            return ResponseEntity.accepted().header("Location", "/reports/" + job).body("{\"status\":\"RUNNING\"}");
        }
        @GetMapping("/reports/{job}") String job(@PathVariable("job") String job) { return "{\"status\":\"" + JOBS.get(job) + "\"}"; }
    }

    static MockHttpServletResponse call(MockMvc mvc, MockHttpServletRequestBuilder req) throws Exception { return mvc.perform(req).andReturn().getResponse(); }
    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) { return b.contentType(MediaType.APPLICATION_JSON).content(body); }
    static String body(MockHttpServletResponse r) throws Exception { return r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version") + " spring=" + org.springframework.core.SpringVersion.getVersion());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Api()).setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8), new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()).build();

        // 一、丢失更新：两个客户端读到同一版本，各自整体替换
        Order o1 = new Order(); o1.address = "北京"; o1.note = ""; ORDERS.put("o-a", o1);
        String seen = body(call(mvc, get("/orders/o-a")));
        call(mvc, json(put("/v1/orders/o-a"), "{\"address\":\"上海\",\"note\":\"\"}"));          // 客服改地址
        call(mvc, json(put("/v1/orders/o-a"), "{\"address\":\"北京\",\"note\":\"放门口\"}"));    // 用户基于旧数据加备注
        out("lost_update", "两个客户端都读到 " + seen + "；客服 PUT 改地址为上海，用户随后基于旧数据 PUT 加备注：最终 " + body(call(mvc, get("/orders/o-a"))) + "，客服的修改丢了，两次都是 200");

        // 二、If-Match：后到的请求得到 412
        Order o2 = new Order(); o2.address = "北京"; o2.note = ""; ORDERS.put("o-b", o2);
        String etag = call(mvc, get("/orders/o-b")).getHeader("ETag");
        var r1 = call(mvc, json(put("/v2/orders/o-b").header("If-Match", etag), "{\"address\":\"上海\",\"note\":\"\"}"));
        var r2 = call(mvc, json(put("/v2/orders/o-b").header("If-Match", etag), "{\"address\":\"北京\",\"note\":\"放门口\"}"));
        out("if_match", "两个客户端都带 If-Match: " + etag + "：第一个 " + r1.getStatus() + "，第二个 " + r2.getStatus() + "（" + body(r2) + "），最终 " + body(call(mvc, get("/orders/o-b"))));
        var r3 = call(mvc, json(put("/v2/orders/o-b"), "{\"address\":\"广州\",\"note\":\"\"}"));
        out("if_match.missing", "不带 If-Match 的 PUT：" + r3.getStatus() + "（" + body(r3) + "）");

        // 三、PUT 缺字段与 PATCH
        Order o3 = new Order(); o3.address = "北京"; o3.note = "放门口"; ORDERS.put("o-c", o3);
        call(mvc, json(put("/v1/orders/o-c"), "{\"address\":\"上海\"}"));
        out("put_missing_field", "PUT 只带 address：" + body(call(mvc, get("/orders/o-c"))) + "，note 被清空：PUT 的语义是整体替换");
        o3.note = "放门口";
        call(mvc, json(patch("/orders/o-c"), "{\"address\":\"深圳\"}"));
        out("patch", "PATCH 只带 address：" + body(call(mvc, get("/orders/o-c"))));

        // 四、改状态：状态字段 vs 命令端点
        Order o4 = new Order(); o4.address = "北京"; o4.status = "SHIPPED"; ORDERS.put("o-d", o4);
        call(mvc, json(put("/v1/orders/o-d"), "{\"address\":\"北京\",\"note\":null,\"status\":\"CANCELLED\"}"));
        out("status_field", "已发货的订单，PUT 把 status 写成 CANCELLED：" + body(call(mvc, get("/orders/o-d"))) + "，没有经过「已发货不能取消」");
        o4.status = "SHIPPED";
        var c = call(mvc, post("/orders/o-d/cancellation"));
        out("command", "改用 POST /orders/o-d/cancellation：" + c.getStatus() + "（" + body(c) + "）");

        // 五、POST 重试
        int n0 = ORDERS.size();
        call(mvc, json(post("/orders"), "{\"address\":\"杭州\"}")); call(mvc, json(post("/orders"), "{\"address\":\"杭州\"}"));
        int plain = ORDERS.size() - n0;
        int before = ORDERS.size();
        var k1 = call(mvc, json(post("/orders").header("Idempotency-Key", "k-1"), "{\"address\":\"杭州\"}"));
        var k2 = call(mvc, json(post("/orders").header("Idempotency-Key", "k-1"), "{\"address\":\"杭州\"}"));
        out("idempotency", "响应丢失后客户端重发同一个 POST：不带幂等键新增 " + plain + " 个订单；带同一个 Idempotency-Key：第一次 " + k1.getHeader("Location") + "，重发 " + k2.getHeader("Location") + "，新增 " + (ORDERS.size() - before) + " 个");

        // 六、受理
        var a = call(mvc, post("/reports"));
        out("accepted", "生成报表：" + a.getStatus() + "，Location " + a.getHeader("Location") + "，状态资源 " + body(call(mvc, get(a.getHeader("Location")))));
    }
}
