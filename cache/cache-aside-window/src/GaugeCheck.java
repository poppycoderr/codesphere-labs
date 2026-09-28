import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;

/** 不一致率指标的两种写法：每次都调用 gauge(name, 装箱值)，与注册一次、更新字段。 */
public class GaugeCheck {
    public static void main(String[] args) throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.gauge("cache.mismatch.rate.naive", Double.valueOf(0.1));
        registry.gauge("cache.mismatch.rate.naive", Double.valueOf(0.2));     // 同名第二次注册
        double afterSecond = registry.get("cache.mismatch.rate.naive").gauge().value();
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        double afterGc = registry.get("cache.mismatch.rate.naive").gauge().value();
        System.out.printf("naive\t每次调用 gauge(name, 装箱值)：第二次传入 0.2 后读数 %s；GC 之后读数 %s%n", afterSecond, afterGc);

        AtomicReference<Double> rate = new AtomicReference<>(0.1);
        registry.gauge("cache.mismatch.rate", rate, r -> r.get());
        rate.set(0.2);
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        System.out.printf("field\t注册一次、更新字段：更新为 0.2 并 GC 之后读数 %s%n", registry.get("cache.mismatch.rate").gauge().value());
    }
}
