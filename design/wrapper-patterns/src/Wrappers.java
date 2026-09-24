import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同一个通知网关上的三种包装：适配器（翻译供应商接口与错误）、装饰器（叠加计量、缓存、重试）、代理（控制访问）。
 * 运行：java Wrappers，每行输出一个可断言的结果。
 */
public class Wrappers {

    record Message(String to, String text) {
    }

    record Receipt(String id) {
    }

    /** 业务层定义的接口。 */
    interface NotificationGateway {
        Receipt send(Message m);
    }

    /** 可重试的失败：限流、超时。 */
    static final class RetryableFailure extends RuntimeException {
        final String vendorCode;

        RetryableFailure(String vendorCode) {
            super(vendorCode);
            this.vendorCode = vendorCode;
        }
    }

    /** 不可重试的失败：号码无效、内容违规。 */
    static final class PermanentFailure extends RuntimeException {
        final String vendorCode;

        PermanentFailure(String vendorCode) {
            super(vendorCode);
            this.vendorCode = vendorCode;
        }
    }

    /** 一律翻译成同一种异常的写法。 */
    static final class SmsFailed extends RuntimeException {
        SmsFailed(String message) {
            super(message);
        }
    }

    // ---------------- 第三方 SDK ----------------
    record VendorResponse(String code, String messageId) {
    }

    /** 模拟的供应商：号码以 000 结尾返回 INVALID_NUMBER；以 9 结尾的号码第一次返回 RATE_LIMITED；其余成功。 */
    static final class VendorSmsClient {
        final AtomicInteger calls = new AtomicInteger();
        final Map<String, Integer> seen = new HashMap<>();

        VendorResponse sendMessage(String e164, String content) {
            calls.incrementAndGet();
            if (e164.endsWith("000")) return new VendorResponse("INVALID_NUMBER", null);
            int n = seen.merge(e164, 1, Integer::sum);
            if (e164.endsWith("9") && n == 1) return new VendorResponse("RATE_LIMITED", null);
            return new VendorResponse("OK", "v-" + calls.get());
        }
    }

    // ---------------- 适配器 ----------------
    record FlatteningAdapter(VendorSmsClient client) implements NotificationGateway {
        public Receipt send(Message m) {
            VendorResponse r = client.sendMessage(m.to(), m.text());
            if (!"OK".equals(r.code())) throw new SmsFailed("短信发送失败");
            return new Receipt(r.messageId());
        }
    }

    record PreciseAdapter(VendorSmsClient client) implements NotificationGateway {
        public Receipt send(Message m) {
            VendorResponse r = client.sendMessage(m.to(), m.text());
            return switch (r.code()) {
                case "OK" -> new Receipt(r.messageId());
                case "RATE_LIMITED", "TIMEOUT" -> throw new RetryableFailure(r.code());
                default -> throw new PermanentFailure(r.code());
            };
        }
    }

    // ---------------- 装饰器 ----------------
    /** 最多尝试 3 次；retryAll 为 true 时任何异常都重试，否则只重试 RetryableFailure。 */
    record Retry(NotificationGateway delegate, boolean retryAll) implements NotificationGateway {
        public Receipt send(Message m) {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    return delegate.send(m);
                } catch (RuntimeException e) {
                    last = e;
                    if (!retryAll && !(e instanceof RetryableFailure)) throw e;
                }
            }
            throw last;
        }
    }

    static final class Metrics implements NotificationGateway {
        final NotificationGateway delegate;
        int calls;
        int errors;

        Metrics(NotificationGateway delegate) {
            this.delegate = delegate;
        }

        public Receipt send(Message m) {
            calls++;
            try {
                return delegate.send(m);
            } catch (RuntimeException e) {
                errors++;
                throw e;
            }
        }
    }

    static final class Cache implements NotificationGateway {
        final NotificationGateway delegate;
        final Map<Message, Receipt> cache = new HashMap<>();
        int hits;

        Cache(NotificationGateway delegate) {
            this.delegate = delegate;
        }

        public Receipt send(Message m) {
            Receipt r = cache.get(m);
            if (r != null) {
                hits++;
                return r;
            }
            r = delegate.send(m);
            cache.put(m, r);
            return r;
        }
    }

    // ---------------- 代理 ----------------
    interface BatchGateway {
        Receipt send(Message m);

        List<Receipt> sendAll(List<Message> ms);

        Receipt sendChecked(Message m) throws IOException;
    }

    static final class SimpleBatchGateway implements BatchGateway {
        public Receipt send(Message m) {
            return new Receipt("r-" + m.to());
        }

        /** 通过 this 调用 send：不经过代理。 */
        public List<Receipt> sendAll(List<Message> ms) {
            List<Receipt> out = new ArrayList<>();
            for (Message m : ms) out.add(this.send(m));
            return out;
        }

        public Receipt sendChecked(Message m) throws IOException {
            return send(m);
        }
    }

    static Map<String, String> results = new LinkedHashMap<>();

    static void out(String key, Object value) {
        System.out.println(key + "\t" + value);
    }

    public static void main(String[] args) {
        adapters();
        decoratorOrder();
        proxies();
    }

    /** 10 条短信：2 条无效号码，3 条第一次会被限流，其余成功。 */
    static List<Message> batch() {
        List<Message> ms = new ArrayList<>();
        for (String to : List.of("13800000000", "13900000000", "13800000019", "13800000029", "13800000039",
                "13800000011", "13800000012", "13800000013", "13800000014", "13800000015")) {
            ms.add(new Message(to, "验证码 123456"));
        }
        return ms;
    }

    static void adapters() {
        for (String variant : List.of("flatten+retry_all", "precise+retry_retryable")) {
            VendorSmsClient vendor = new VendorSmsClient();
            NotificationGateway g = variant.startsWith("flatten")
                    ? new Retry(new FlatteningAdapter(vendor), true)
                    : new Retry(new PreciseAdapter(vendor), false);
            int ok = 0;
            Map<String, Integer> failures = new LinkedHashMap<>();
            for (Message m : batch()) {
                try {
                    g.send(m);
                    ok++;
                } catch (RuntimeException e) {
                    String code = e instanceof PermanentFailure p ? p.vendorCode : e.getMessage();
                    failures.merge(e.getClass().getSimpleName() + ":" + code, 1, Integer::sum);
                }
            }
            out("adapter." + variant, "成功 " + ok + "，失败 " + failures + "，供应商调用 " + vendor.calls.get() + " 次");
        }
    }

    /** 每条消息第一次发送失败一次（可重试），同一条消息请求两次。 */
    static final class Flaky implements NotificationGateway {
        final Map<Message, Integer> attempts = new HashMap<>();
        int calls;

        public Receipt send(Message m) {
            calls++;
            if (attempts.merge(m, 1, Integer::sum) == 1) throw new RetryableFailure("TIMEOUT");
            return new Receipt("r-" + calls);
        }
    }

    static void decoratorOrder() {
        List<Message> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Message m = new Message("1380000000" + i, "hi");
            requests.add(m);
            requests.add(m);
        }
        {
            Flaky d = new Flaky();
            Metrics metrics = new Metrics(d);
            Cache cache = new Cache(metrics);
            NotificationGateway g = new Retry(cache, false);
            requests.forEach(g::send);
            out("order.retry(cache(metrics(delegate)))", "metrics 计数 " + metrics.calls + "、错误 " + metrics.errors
                    + "；缓存命中 " + cache.hits + "；下游调用 " + d.calls);
        }
        {
            Flaky d = new Flaky();
            Retry retry = new Retry(d, false);
            Cache cache = new Cache(retry);
            Metrics metrics = new Metrics(cache);
            requests.forEach(metrics::send);
            out("order.metrics(cache(retry(delegate)))", "metrics 计数 " + metrics.calls + "、错误 " + metrics.errors
                    + "；缓存命中 " + cache.hits + "；下游调用 " + d.calls);
        }
    }

    static void proxies() {
        AtomicInteger intercepted = new AtomicInteger();
        SimpleBatchGateway target = new SimpleBatchGateway();
        InvocationHandler h = (proxy, method, args) -> {
            intercepted.incrementAndGet();
            if (method.getName().equals("sendChecked")) throw new IOException("供应商连接被拒绝");
            if (method.getName().equals("send") && ((Message) args[0]).to().startsWith("blocked")) {
                throw new IOException("未声明的受检异常");
            }
            return method.invoke(target, args);
        };
        BatchGateway p = (BatchGateway) Proxy.newProxyInstance(BatchGateway.class.getClassLoader(), new Class<?>[] {BatchGateway.class}, h);
        p.sendAll(List.of(new Message("a", "x"), new Message("b", "x"), new Message("c", "x")));
        out("proxy.self_invocation", "sendAll 发送 3 条，经过代理的调用 " + intercepted.get() + " 次");
        try {
            p.sendChecked(new Message("a", "x"));
        } catch (IOException e) {
            out("proxy.declared_checked", "sendChecked 声明了 IOException：调用方收到 " + e.getClass().getSimpleName());
        }
        try {
            p.send(new Message("blocked", "x"));
        } catch (UndeclaredThrowableException e) {
            out("proxy.undeclared_checked", "send 没有声明 IOException：调用方收到 " + e.getClass().getSimpleName()
                    + "，原因 " + e.getCause().getClass().getSimpleName());
        }
    }
}
