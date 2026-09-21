import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouterTest {
    static final Target PHONE = new Target("phone", "oncall-payment");
    static final Target CHAT = new Target("chat", "payment-alerts");
    static Alert alert(String team, Severity s) { return new Alert("pay-api", team, s, "fp-1", Instant.EPOCH); }
    static Router router() {
        return new Router(List.of(
            new Route("支付高优先级", a -> a.team().equals("payment") && a.severity().compareTo(Severity.P2) <= 0, List.of(PHONE, CHAT)),
            new Route("支付全部", a -> a.team().equals("payment"), List.of(CHAT))));
    }

    // 需求在写代码之前先写成测试
    @Test void 支付P1打电话并发群消息() { assertEquals(List.of(PHONE, CHAT), router().route(alert("payment", Severity.P1))); }
    @Test void 支付P3只发群消息() { assertEquals(List.of(CHAT), router().route(alert("payment", Severity.P3))); }
    @Test void 没有匹配的路由就不通知() { assertEquals(List.of(), router().route(alert("search", Severity.P1))); }

    // 变化 1：新增渠道不需要修改 Router
    @Test void 新渠道只需注册实现() {
        List<String> log = new ArrayList<>();
        Channel phone = new Recording("phone", log), chat = new Recording("chat", log);
        Dispatcher d = new Dispatcher(router(), List.of(phone, chat), List.of(), Clock.systemUTC());
        d.dispatch(alert("payment", Severity.P1));
        assertEquals(List.of("phone->oncall-payment", "chat->payment-alerts"), log);
    }
    @Test void 渠道没注册在发送时报错() {
        Dispatcher d = new Dispatcher(router(), List.of(new Recording("chat", new ArrayList<>())), List.of(), Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> d.dispatch(alert("payment", Severity.P1)));
    }

    // 变化 2：去重与静默，用固定时钟验证
    @Test void 十分钟内同一告警只发一次() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T02:00:00Z"));
        List<String> log = new ArrayList<>();
        Dispatcher d = new Dispatcher(router(), List.of(new Recording("phone", log), new Recording("chat", log)), List.of(new Dedup(Duration.ofMinutes(10))), clock);
        d.dispatch(alert("payment", Severity.P1));
        clock.plus(Duration.ofMinutes(9));
        assertEquals(List.of(), d.dispatch(alert("payment", Severity.P1)));
        clock.plus(Duration.ofMinutes(1));
        assertEquals(2, d.dispatch(alert("payment", Severity.P1)).size());
    }
    @Test void 夜间静默P3但不静默P1() {
        Clock night = Clock.fixed(Instant.parse("2026-09-21T15:30:00Z"), ZoneOffset.UTC);   // 上海 23:30
        QuietHours q = new QuietHours(ZoneId.of("Asia/Shanghai"), LocalTime.of(22, 0), LocalTime.of(8, 0), Severity.P3);
        Dispatcher d = new Dispatcher(router(), List.of(new Recording("phone", new ArrayList<>()), new Recording("chat", new ArrayList<>())), List.of(q), night);
        assertEquals(List.of(), d.dispatch(alert("payment", Severity.P3)));
        assertEquals(List.of(PHONE, CHAT), d.dispatch(alert("payment", Severity.P1)));
    }
    @Test void 静默区间边界() {
        QuietHours q = new QuietHours(ZoneId.of("Asia/Shanghai"), LocalTime.of(22, 0), LocalTime.of(8, 0), Severity.P3);
        Alert a = alert("payment", Severity.P3);
        assertTrue(q.suppress(a, CHAT, Instant.parse("2026-09-21T14:00:00Z")));    // 22:00 开始静默
        assertTrue(q.suppress(a, CHAT, Instant.parse("2026-09-21T23:59:00Z")));    // 07:59
        assertFalse(q.suppress(a, CHAT, Instant.parse("2026-09-22T00:00:00Z")));   // 08:00 恢复
        assertFalse(q.suppress(a, CHAT, Instant.parse("2026-09-21T13:59:00Z")));   // 21:59
    }

    // 变化 3：规则文本解析成同一个模型；行为与代码定义等价
    static final String RULES = """
        # 值班负责人维护
        route 支付高优先级: team=payment severity<=P2 -> phone:oncall-payment, chat:payment-alerts
        route 支付全部: team=payment -> chat:payment-alerts
        """;
    @Test void 文本规则与代码规则等价() {
        Router fromText = new Router(RuleText.parse(RULES, Set.of("phone", "chat")));
        for (Severity s : Severity.values()) for (String team : List.of("payment", "search"))
            assertEquals(router().route(alert(team, s)), fromText.route(alert(team, s)), team + " " + s);
    }
    @Test void 规则写错在加载时报出全部错误() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RuleText.parse("""
            route a: team=payment severity<=P5 -> phone:x
            route b: team=payment -> pager:y
            """, Set.of("phone", "chat")));
        assertTrue(e.getMessage().contains("第 1 行条件无法识别：severity<=P5"), e.getMessage());
        assertTrue(e.getMessage().contains("第 2 行渠道不存在：pager:y"), e.getMessage());
    }

    record Recording(String name, List<String> log) implements Channel {
        public void send(Target t, Alert a) { log.add(t.channel() + "->" + t.address()); }
    }
    static final class MutableClock extends Clock {
        private Instant now; MutableClock(Instant now) { this.now = now; }
        void plus(Duration d) { now = now.plus(d); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }
}
