import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 请求里的哪些字段该由客户端决定：直接把请求体绑定到领域对象、绑定到命令对象、身份从可信上下文取、按租户查找。
 * Spring MVC 7.0.9 的 MockMvc 在进程内执行完整的请求绑定，不经过网络。
 */
public class CommandLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    // 领域对象：有些字段只应由系统决定
    public static class Order {
        public String tenantId, ownerId, sku, status = "NEW"; public int qty;
        @Override public String toString() { return "Order[tenant=" + tenantId + ", owner=" + ownerId + ", sku=" + sku + ", qty=" + qty + ", status=" + status + "]"; }
    }
    static final List<Order> SAVED = new ArrayList<>();

    // 命令：只包含客户端有权决定的部分
    public record PlaceOrder(String sku, int qty) { }
    // 表单绑定用的可变对象
    public static class ProfileForm { private String nickname, role = "member"; public void setNickname(String n) { nickname = n; } public void setRole(String r) { role = r; } public String toString() { return "nickname=" + nickname + ", role=" + role; } }

    @RestController
    public static class Api {
        /** 写法一：请求体直接绑定到领域对象 */
        @PostMapping("/v1/orders") public String placeV1(@RequestBody Order o, @RequestAttribute("tenant") String tenant) {
            if (o.tenantId == null) o.tenantId = tenant;
            SAVED.add(o); return o.toString();
        }
        /** 写法二：命令对象 + 可信上下文里的身份 */
        @PostMapping("/v2/orders") public String placeV2(@RequestBody PlaceOrder cmd, @RequestAttribute("tenant") String tenant, @RequestAttribute("user") String user) {
            Order o = new Order(); o.tenantId = tenant; o.ownerId = user; o.sku = cmd.sku(); o.qty = cmd.qty();
            SAVED.add(o); return o.toString();
        }
        /** 按 id 查找，不带租户条件 */
        @GetMapping("/v1/orders/{i}") public String getV1(@PathVariable("i") int i) { return SAVED.get(i).toString(); }
        /** 按（租户，id）查找 */
        @GetMapping("/v2/orders/{i}") public String getV2(@PathVariable("i") int i, @RequestAttribute("tenant") String tenant) {
            return Optional.of(SAVED.get(i)).filter(o -> o.tenantId.equals(tenant)).map(Order::toString).orElse("not found");
        }
        @PostMapping("/v1/profile") public String profileV1(@ModelAttribute ProfileForm f) { return f.toString(); }
        @PostMapping("/v2/profile") public String profileV2(@ModelAttribute ProfileForm f) { return f.toString(); }
        @InitBinder("profileForm") void restrict(WebDataBinder b, jakarta.servlet.http.HttpServletRequest req) { if (req.getRequestURI().startsWith("/v2")) b.setAllowedFields("nickname"); }
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version") + " spring=" + org.springframework.core.SpringVersion.getVersion());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Api()).build();
        // 认证过滤器已经把可信身份放进请求属性：当前租户 t-alice、用户 alice
        String body1 = "{\"sku\":\"A1\",\"qty\":2,\"tenantId\":\"t-bob\",\"ownerId\":\"bob\",\"status\":\"PAID\"}";
        out("bind.entity", "请求体直接绑定到领域对象，客户端在 JSON 里带上 tenantId、ownerId、status：保存的是 " + call(mvc, post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body1)));
        out("bind.command", "绑定到只含 sku、qty 的命令对象，身份取自请求上下文，同样的 JSON：保存的是 " + call(mvc, post("/v2/orders").contentType(MediaType.APPLICATION_JSON).content(body1)));

        String bobOrder = call(mvc, post("/v2/orders").contentType(MediaType.APPLICATION_JSON).content("{\"sku\":\"B9\",\"qty\":1}"), "t-bob", "bob");
        int bobIndex = SAVED.size() - 1;
        out("lookup.by_id", "租户 t-alice 的用户按 id 读取 t-bob 的订单（" + bobOrder.substring(0, 22) + "…），只按 id 查：" + call(mvc, get("/v1/orders/" + bobIndex)));
        out("lookup.by_tenant", "按（租户，id）查：" + call(mvc, get("/v2/orders/" + bobIndex)));

        out("form.open", "表单绑定到有 setRole 的对象，请求参数里多带 role=admin：" + call(mvc, post("/v1/profile").param("nickname", "al").param("role", "admin")));
        out("form.allowed", "在 @InitBinder 里只允许 nickname：" + call(mvc, post("/v2/profile").param("nickname", "al").param("role", "admin")));
    }

    static String call(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder req) throws Exception { return call(mvc, req, "t-alice", "alice"); }
    static String call(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder req, String tenant, String user) throws Exception {
        var builder = (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) req;
        builder.requestAttr("tenant", tenant).requestAttr("user", user);
        return mvc.perform(builder).andReturn().getResponse().getContentAsString();
    }
}
