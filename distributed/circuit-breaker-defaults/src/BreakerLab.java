import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 熔断器的默认配置在什么情况下不动作、在什么情况下误动作。用 Resilience4j 的 CircuitBreaker 直接验证：
 * 默认值、最少调用数、打开后的拒绝、半开状态的放行数与卡住、哪些异常算失败、慢调用、多个接口共用一个熔断器。
 * 调用耗时通过 onSuccess / onError 直接上报，不真的等待；只有「打开后等多久」用了 200 毫秒的真实等待。
 */
public class BreakerLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static final class BusinessException extends RuntimeException { BusinessException(String m) { super(m); } }

    static void fail(CircuitBreaker cb, int n, Throwable t) { for (int i = 0; i < n; i++) if (cb.tryAcquirePermission()) cb.onError(5, TimeUnit.MILLISECONDS, t); }
    static void ok(CircuitBreaker cb, int n, long millis) { for (int i = 0; i < n; i++) if (cb.tryAcquirePermission()) cb.onSuccess(millis, TimeUnit.MILLISECONDS); }
    static String state(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        return cb.getState() + "（窗口内 " + m.getNumberOfBufferedCalls() + " 次，失败 " + m.getNumberOfFailedCalls() + "，慢 " + m.getNumberOfSlowCalls() + "，失败率 " + m.getFailureRate() + "）";
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version") + " resilience4j=" + CircuitBreaker.class.getPackage().getImplementationVersion());
        CircuitBreakerConfig d = CircuitBreakerConfig.ofDefaults();
        out("defaults.window", "滑动窗口 " + d.getSlidingWindowType() + "，大小 " + d.getSlidingWindowSize() + "，最少调用数 " + d.getMinimumNumberOfCalls());
        out("defaults.thresholds", "失败率阈值 " + d.getFailureRateThreshold() + "%，慢调用阈值 " + d.getSlowCallDurationThreshold().toSeconds() + " 秒，慢调用比例阈值 " + d.getSlowCallRateThreshold() + "%");
        out("defaults.open", "打开后等待 " + d.getWaitIntervalFunctionInOpenState().apply(1) / 1000 + " 秒，到时自动转半开 = " + d.isAutomaticTransitionFromOpenToHalfOpenEnabled());
        out("defaults.half_open", "半开时放行 " + d.getPermittedNumberOfCallsInHalfOpenState() + " 次，半开状态最长停留 " + d.getMaxWaitDurationInHalfOpenState().toSeconds() + " 秒（0 表示不限）");

        // 一、最少调用数：窗口没攒够之前，全部失败也不打开
        CircuitBreaker cb = CircuitBreaker.of("default", d);
        fail(cb, 99, new IOException("connect timed out"));
        out("minimum.99", "默认配置，连续失败 99 次：" + state(cb));
        fail(cb, 1, new IOException("connect timed out"));
        out("minimum.100", "第 100 次失败：" + state(cb));

        // 二、打开之后：不调用下游，直接拒绝
        AtomicInteger invoked = new AtomicInteger();
        Supplier<String> downstream = () -> { invoked.incrementAndGet(); return "ok"; };
        int rejected = 0;
        for (int i = 0; i < 50; i++) { try { cb.executeSupplier(downstream); } catch (CallNotPermittedException e) { rejected++; } }
        out("open.reject", "打开期间调用 50 次：下游实际被调用 " + invoked.get() + " 次，" + rejected + " 次抛 CallNotPermittedException");

        // 三、窗口里有成功记录时，要多少次失败才打开
        cb = CircuitBreaker.of("diluted", d);
        ok(cb, 100, 5);
        fail(cb, 49, new IOException("503"));
        out("dilute.49", "先成功 100 次，随后连续失败 49 次：" + state(cb));
        fail(cb, 1, new IOException("503"));
        out("dilute.50", "第 50 次连续失败：" + state(cb));

        // 四、打开到半开：等待时间到了之后，状态什么时候变
        CircuitBreakerConfig fast = CircuitBreakerConfig.custom().waitDurationInOpenState(Duration.ofMillis(200)).build();
        cb = CircuitBreaker.of("half-open", fast);
        fail(cb, 100, new IOException("503"));
        Thread.sleep(400);
        out("half_open.lazy", "等待时间（200 ms）过去 400 ms 后、没有新调用时的状态：" + cb.getState());
        int permitted = 0, denied = 0;
        for (int i = 0; i < 15; i++) { if (cb.tryAcquirePermission()) permitted++; else denied++; }
        out("half_open.permits", "此时同时来 15 个调用（都还没返回）：放行 " + permitted + " 个，拒绝 " + denied + " 个，状态 " + cb.getState());
        Thread.sleep(400);
        out("half_open.stuck", "这 10 个调用一直不返回，再过 400 ms：状态 " + cb.getState() + "，新的调用被放行 = " + cb.tryAcquirePermission());
        for (int i = 0; i < 5; i++) cb.onSuccess(5, TimeUnit.MILLISECONDS);
        for (int i = 0; i < 5; i++) cb.onError(5, TimeUnit.MILLISECONDS, new IOException("503"));
        out("half_open.result_5_of_10", "10 个试探调用里 5 个失败：" + cb.getState());
        Thread.sleep(300);
        for (int i = 0; i < 10; i++) cb.tryAcquirePermission();
        for (int i = 0; i < 6; i++) cb.onSuccess(5, TimeUnit.MILLISECONDS);
        for (int i = 0; i < 4; i++) cb.onError(5, TimeUnit.MILLISECONDS, new IOException("503"));
        out("half_open.result_4_of_10", "再次半开，10 个试探调用里 4 个失败：" + cb.getState());

        // 五、哪些异常算失败
        cb = CircuitBreaker.of("business", d);
        ok(cb, 40, 5);
        fail(cb, 60, new BusinessException("余额不足"));
        out("exceptions.default", "默认配置，100 次调用里 60 次抛业务异常（余额不足），下游本身正常：" + state(cb));
        cb = CircuitBreaker.of("business-ignored", CircuitBreakerConfig.custom().ignoreExceptions(BusinessException.class).build());
        ok(cb, 40, 5);
        fail(cb, 60, new BusinessException("余额不足"));
        out("exceptions.ignored", "ignoreExceptions(BusinessException)：" + state(cb));
        cb = CircuitBreaker.of("record-only-io", CircuitBreakerConfig.custom().recordExceptions(IOException.class).build());
        ok(cb, 40, 5);
        fail(cb, 60, new BusinessException("余额不足"));
        out("exceptions.record_only", "recordExceptions(IOException)：" + state(cb));

        // 六、慢但不报错
        cb = CircuitBreaker.of("slow-default", d);
        ok(cb, 100, 30_000);
        out("slow.default", "默认配置，100 次调用每次 30 秒才成功返回：" + state(cb));
        cb = CircuitBreaker.of("slow-tuned", CircuitBreakerConfig.custom().slowCallDurationThreshold(Duration.ofSeconds(2)).slowCallRateThreshold(50).build());
        ok(cb, 100, 30_000);
        out("slow.tuned", "慢调用阈值 2 秒、比例阈值 50%：" + state(cb));

        // 七、两个接口共用一个熔断器
        cb = CircuitBreaker.of("shared", d);
        for (int i = 0; i < 50; i++) { fail(cb, 1, new IOException("导出接口超时")); ok(cb, 1, 5); }
        boolean queryPermitted = cb.tryAcquirePermission();
        out("shared.one_breaker", "导出接口全部失败、查询接口全部成功，各 50 次，共用一个熔断器：" + state(cb) + "；查询接口的下一次调用被放行 = " + queryPermitted);
        CircuitBreaker export = CircuitBreaker.of("export", d), query = CircuitBreaker.of("query", d);
        fail(export, 100, new IOException("导出接口超时")); ok(query, 100, 5);
        out("shared.per_endpoint", "各用各的熔断器，各 100 次：导出 " + export.getState() + "，查询 " + query.getState());

        // 八、十个实例里坏了一个
        cb = CircuitBreaker.of("one-of-ten", d);
        for (int i = 0; i < 100; i++) { if (i % 10 == 0) fail(cb, 1, new IOException("实例 3 无响应")); else ok(cb, 1, 5); }
        out("one_of_ten", "按服务名建的熔断器，10 个实例里 1 个全部失败：" + state(cb));
    }
}
