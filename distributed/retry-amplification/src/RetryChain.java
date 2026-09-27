import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 三级调用链：驱动程序 → A → B → C，都在同一个 JVM 里，用 JDK 自带的 HttpServer。输出为「键<TAB>事实」。
 * 统计的是 C 实际收到的请求数、同时在处理的最大请求数，以及用户放弃之后 C 仍然做完的工作。
 */
public class RetryChain {

    /** 一跳的调用策略：尝试次数、单次超时、是否按预算重试、是否传递截止时间。 */
    record Hop(int attempts, Duration timeout, boolean budgeted, boolean propagate) {
    }

    /** 重试预算：重试次数不超过请求数的 10%，按这一跳累计。 */
    static class Budget {
        final AtomicLong requests = new AtomicLong();
        final AtomicLong retries = new AtomicLong();

        boolean allowRetry() {
            if (retries.get() + 1 > 0.1 * requests.get()) return false;
            retries.incrementAndGet();
            return true;
        }
    }

    static volatile Hop aToB, bToC;
    static volatile String cMode = "fail";          // fail：立刻返回 503；slow：处理 2.5 秒后返回 200
    static final Budget budgetA = new Budget(), budgetB = new Budget();
    static final AtomicInteger cRequests = new AtomicInteger(), cActive = new AtomicInteger(), cMaxActive = new AtomicInteger();
    static final AtomicInteger cCompleted = new AtomicInteger(), cRefused = new AtomicInteger();
    static final HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static final long SLOW_MS = 2500;

    public static void main(String[] args) throws Exception {
        start(9101, ex -> relay(ex, "http://127.0.0.1:9102/", () -> aToB, budgetA));
        start(9102, ex -> relay(ex, "http://127.0.0.1:9103/", () -> bToC, budgetB));
        start(9103, RetryChain::serviceC);

        // 1. C 持续快速失败，100 个用户请求
        Hop none = new Hop(1, Duration.ofSeconds(5), false, false);
        failFast("every-layer", new Hop(3, Duration.ofSeconds(5), false, false), new Hop(3, Duration.ofSeconds(5), false, false), 3);
        failFast("entry-only", none, none, 3);
        failFast("budgeted", new Hop(3, Duration.ofSeconds(5), true, false), new Hop(3, Duration.ofSeconds(5), true, false), 3);

        // 2. C 很慢，超时倒挂：用户 1 秒，A→B 2 秒，B→C 3 秒；不重试，比较是否传递截止时间
        slow("inverted", new Hop(1, Duration.ofSeconds(2), false, false), new Hop(1, Duration.ofSeconds(3), false, false), 1, false);
        slow("deadline", new Hop(1, Duration.ofSeconds(2), false, true), new Hop(1, Duration.ofSeconds(3), false, true), 1, true);

        // 3. C 很慢，超时倒挂，每层都重试 3 次：一个用户请求让 C 开始了多少次处理
        slow("inverted-retries", new Hop(3, Duration.ofSeconds(2), false, false), new Hop(3, Duration.ofSeconds(3), false, false), 3, false);
        System.exit(0);
    }

    interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    static void start(int port, Handler h) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 200);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/", ex -> {
            try {
                h.handle(ex);
            } catch (Exception e) {
                reply(ex, 500);
            }
        });
        s.start();
    }

    static void reply(HttpExchange ex, int status) {
        try (ex) {
            ex.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // 上游已经放弃，连接可能已关闭
        }
    }

    /** A、B 的处理：按策略调用下一跳，成功返回 200，否则 502。 */
    static void relay(HttpExchange ex, String next, java.util.function.Supplier<Hop> hopRef, Budget budget) throws Exception {
        Hop hop = hopRef.get();
        String deadlineHeader = ex.getRequestHeaders().getFirst("X-Deadline");
        long deadline = deadlineHeader == null ? Long.MAX_VALUE : Long.parseLong(deadlineHeader);
        reply(ex, call(next, hop, budget, deadline) ? 200 : 502);
    }

    /** 调用一跳：失败或超时后按策略重试；传递截止时间时，单次超时取「自己的超时」和「剩余时间」中较小的一个。 */
    static boolean call(String url, Hop hop, Budget budget, long deadline) throws InterruptedException {
        if (budget != null) budget.requests.incrementAndGet();
        for (int attempt = 1; attempt <= hop.attempts(); attempt++) {
            if (attempt > 1 && hop.budgeted() && !budget.allowRetry()) return false;
            long timeout = hop.timeout().toMillis();
            var req = HttpRequest.newBuilder(URI.create(url)).GET();
            if (hop.propagate()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                timeout = Math.min(timeout, remaining);
                req.header("X-Deadline", Long.toString(Math.min(deadline, System.currentTimeMillis() + timeout)));
            }
            req.timeout(Duration.ofMillis(timeout));
            try {
                if (http.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return true;
            } catch (IOException e) {
                // 超时或连接错误：按策略重试
            }
        }
        return false;
    }

    static void serviceC(HttpExchange ex) throws Exception {
        cRequests.incrementAndGet();
        cMaxActive.accumulateAndGet(cActive.incrementAndGet(), Math::max);
        try {
            if (cMode.equals("fail")) {
                reply(ex, 503);
                return;
            }
            String d = ex.getRequestHeaders().getFirst("X-Deadline");
            if (d != null && Long.parseLong(d) - System.currentTimeMillis() < SLOW_MS) {
                cRefused.incrementAndGet();             // 剩余时间不够完成这次处理：直接拒绝，不开始干活
                reply(ex, 504);
                return;
            }
            Thread.sleep(SLOW_MS);
            cCompleted.incrementAndGet();
            reply(ex, 200);
        } finally {
            cActive.decrementAndGet();
        }
    }

    static void reset(Hop ab, Hop bc, String mode) {
        aToB = ab;
        bToC = bc;
        cMode = mode;
        for (Budget b : List.of(budgetA, budgetB)) {
            b.requests.set(0);
            b.retries.set(0);
        }
        for (AtomicInteger c : List.of(cRequests, cActive, cMaxActive, cCompleted, cRefused)) c.set(0);
    }

    static void failFast(String name, Hop ab, Hop bc, int userAttempts) throws Exception {
        reset(ab, bc, "fail");
        Budget userBudget = new Budget();
        Hop user = new Hop(userAttempts, Duration.ofSeconds(5), ab.budgeted(), false);
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            if (call("http://127.0.0.1:9101/", user, userBudget, Long.MAX_VALUE)) ok++;
        }
        System.out.printf("fail.%s\tC 持续返回 503，100 个用户请求：用户成功 %d 个，C 收到 %d 个请求（每个用户请求 %.1f 个）%n",
                name, ok, cRequests.get(), cRequests.get() / 100.0);
    }

    static void slow(String name, Hop ab, Hop bc, int userAttempts, boolean propagate) throws Exception {
        reset(ab, bc, "slow");
        long t0 = System.currentTimeMillis();
        Hop user = new Hop(userAttempts, Duration.ofSeconds(1), false, propagate);
        boolean ok = call("http://127.0.0.1:9101/", user, null, propagate ? t0 + 1000L * userAttempts : Long.MAX_VALUE);
        long userMs = System.currentTimeMillis() - t0;
        Thread.sleep(9000);                                 // 等链路上还在进行的调用都结束
        System.out.printf("slow.%s\tC 每次处理 2.5 秒，用户每次等 1 秒、共尝试 %d 次：用户在 %d ms 时%s；C 收到 %d 个请求，做完 %d 次（用户都已放弃），因截止时间不够而拒绝 %d 次，同时处理最多 %d 个%n",
                name, userAttempts, userMs, ok ? "成功" : "放弃", cRequests.get(), cCompleted.get(), cRefused.get(), cMaxActive.get());
    }
}
