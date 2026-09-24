import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 活动报名请求上的三种扩展方式：策略（按活动类型选择容量规则）、固定流程加回调、责任链（租户、鉴权、限流、幂等、校验）。
 * 运行：java Pipeline，每行输出一个可断言的结果。
 */
public class Pipeline {

    enum ActivityType { MEETUP, WORKSHOP, WEBINAR }

    record Activity(String id, ActivityType type, int seats) {
    }

    // ---------------- 策略 ----------------
    interface CapacityRule {
        ActivityType type();

        int capacity(Activity a);
    }

    record FixedSeats(ActivityType type) implements CapacityRule {
        public int capacity(Activity a) {
            return a.seats();
        }
    }

    record Overbook(ActivityType type, int percent) implements CapacityRule {
        public int capacity(Activity a) {
            return a.seats() * (100 + percent) / 100;
        }
    }

    /** 与常见的「filter + findFirst」一样：第一个匹配的策略胜出。 */
    static CapacityRule firstMatch(List<CapacityRule> rules, ActivityType t) {
        return rules.stream().filter(r -> r.type() == t).findFirst().orElseThrow();
    }

    /** 启动时建立索引：重复与缺失都立即失败。 */
    static Map<ActivityType, CapacityRule> strictIndex(List<CapacityRule> rules) {
        Map<ActivityType, CapacityRule> index = new EnumMap<>(ActivityType.class);
        for (CapacityRule r : rules) {
            CapacityRule prev = index.putIfAbsent(r.type(), r);
            if (prev != null) throw new IllegalStateException("重复的容量规则 " + r.type() + "：" + prev + " 与 " + r);
        }
        Set<ActivityType> missing = EnumSet.allOf(ActivityType.class);
        missing.removeAll(index.keySet());
        if (!missing.isEmpty()) throw new IllegalStateException("缺少容量规则 " + missing);
        return index;
    }

    // ---------------- 固定流程 + 回调 ----------------
    static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    /** 固定流程：加载 → 回调计算 → 保存。回调在调用方线程执行。 */
    static <T> T runInline(Function<String, T> step) {
        return step.apply("activity-1");
    }

    /** 同一个流程，但把回调交给线程池执行。 */
    static <T> T runAsync(ExecutorService pool, Function<String, T> step) {
        return CompletableFuture.supplyAsync(() -> step.apply("activity-1"), pool).join();
    }

    // ---------------- 责任链 ----------------
    record Request(String user, boolean authenticated, String idempotencyKey, int partySize) {
    }

    record Decision(boolean pass, String reason) {
        static Decision next() {
            return new Decision(true, null);
        }

        static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }

    interface Handler {
        String name();

        Decision handle(Request r);
    }

    static final class Auth implements Handler {
        public String name() {
            return "auth";
        }

        public Decision handle(Request r) {
            return r.authenticated() ? Decision.next() : Decision.reject("未登录");
        }
    }

    /** 全局配额：每处理一个请求消耗一个名额。 */
    static final class RateLimit implements Handler {
        int remaining;

        RateLimit(int quota) {
            remaining = quota;
        }

        public String name() {
            return "rate-limit";
        }

        public Decision handle(Request r) {
            if (remaining <= 0) return Decision.reject("超过限流");
            remaining--;
            return Decision.next();
        }
    }

    /** 记住已见过的幂等键：返回「继续」之前就写入了状态。 */
    static final class Idempotency implements Handler {
        final Set<String> seen = new HashSet<>();

        public String name() {
            return "idempotency";
        }

        public Decision handle(Request r) {
            return seen.add(r.idempotencyKey()) ? Decision.next() : Decision.reject("重复请求");
        }
    }

    static final class Validation implements Handler {
        public String name() {
            return "validation";
        }

        public Decision handle(Request r) {
            return r.partySize() >= 1 && r.partySize() <= 4 ? Decision.next() : Decision.reject("同行人数必须在 1—4 之间");
        }
    }

    /** 顺序执行，遇到拒绝就短路，并记录每个节点的决定。 */
    static Decision run(List<Handler> chain, Request r, List<String> trace) {
        for (Handler h : chain) {
            Decision d = h.handle(r);
            trace.add(h.name() + "=" + (d.pass() ? "pass" : "reject(" + d.reason() + ")"));
            if (!d.pass()) return d;
        }
        return Decision.next();
    }

    static void out(String key, Object value) {
        System.out.println(key + "\t" + value);
    }

    public static void main(String[] args) throws Exception {
        strategies();
        callbacks();
        chainOrder();
        chainSideEffect();
    }

    static void strategies() {
        Activity workshop = new Activity("w1", ActivityType.WORKSHOP, 20);
        List<CapacityRule> rules = new ArrayList<>(List.of(new FixedSeats(ActivityType.MEETUP), new FixedSeats(ActivityType.WORKSHOP),
                new FixedSeats(ActivityType.WEBINAR), new Overbook(ActivityType.WORKSHOP, 10)));
        int a = firstMatch(rules, ActivityType.WORKSHOP).capacity(workshop);
        List<CapacityRule> reordered = new ArrayList<>(rules);
        java.util.Collections.reverse(reordered);
        int b = firstMatch(reordered, ActivityType.WORKSHOP).capacity(workshop);
        out("strategy.first_match", "20 个座位的工作坊：注册顺序 A 容量 " + a + "，顺序反过来容量 " + b);
        try {
            strictIndex(rules);
        } catch (IllegalStateException e) {
            out("strategy.strict_duplicate", e.getMessage());
        }
        try {
            strictIndex(List.of(new FixedSeats(ActivityType.MEETUP), new FixedSeats(ActivityType.WORKSHOP)));
        } catch (IllegalStateException e) {
            out("strategy.strict_missing", e.getMessage());
        }
    }

    static void callbacks() throws Exception {
        TENANT.set("tenant-a");
        String inline = runInline(id -> id + " 的租户：" + TENANT.get());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            String async = runAsync(pool, id -> id + " 的租户：" + TENANT.get());
            out("callback.inline", inline);
            out("callback.async", async);
            try {
                runAsync(pool, id -> {
                    throw new IllegalStateException("容量规则不存在");
                });
            } catch (CompletionException e) {
                out("callback.async_exception", "调用方收到 " + e.getClass().getSimpleName() + "，原因 "
                        + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
        } finally {
            pool.shutdown();
            TENANT.remove();
        }
    }

    /** 40 个合法请求与 60 个未登录请求交替到达，全局配额 50。 */
    static void chainOrder() {
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            boolean legit = i % 5 < 2;
            requests.add(new Request((legit ? "user-" : "bot-") + i, legit, "k-" + i, 2));
        }
        for (String order : List.of("auth,rate-limit", "rate-limit,auth")) {
            List<Handler> chain = order.startsWith("auth")
                    ? List.of(new Auth(), new RateLimit(50), new Idempotency(), new Validation())
                    : List.of(new RateLimit(50), new Auth(), new Idempotency(), new Validation());
            int legitOk = 0;
            Map<String, Integer> reasons = new LinkedHashMap<>();
            for (Request r : requests) {
                Decision d = run(chain, r, new ArrayList<>());
                if (d.pass() && r.authenticated()) legitOk++;
                if (!d.pass()) reasons.merge(d.reason(), 1, Integer::sum);
            }
            out("chain.order." + order, "合法请求通过 " + legitOk + " / 40，拒绝原因 " + reasons);
        }
    }

    static void chainSideEffect() {
        for (String order : List.of("idempotency,validation", "validation,idempotency")) {
            List<Handler> chain = order.startsWith("idempotency")
                    ? List.of(new Auth(), new Idempotency(), new Validation())
                    : List.of(new Auth(), new Validation(), new Idempotency());
            List<String> t1 = new ArrayList<>();
            List<String> t2 = new ArrayList<>();
            Decision first = run(chain, new Request("alice", true, "order-7", 6), t1);
            Decision retry = run(chain, new Request("alice", true, "order-7", 2), t2);
            out("chain.side_effect." + order, "第一次（6 人）" + t1 + "；改成 2 人后用同一个幂等键重试 " + t2
                    + " → " + (retry.pass() ? "通过" : "拒绝：" + retry.reason()));
        }
    }
}
