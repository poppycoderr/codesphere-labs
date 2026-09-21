package after;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Optional;

/** 业务核心：只依赖 JDK，不知道 HTTP、JSON、JDBC 的存在 */
public final class Ordering {
    private Ordering() {}

    public record OrderRequest(String userId, String sku, int quantity, boolean member) {
        public OrderRequest {
            if (quantity < 1 || quantity > 99) throw new IllegalArgumentException("quantity out of range: " + quantity);
        }
    }

    public record Order(String orderNo, String userId, String sku, int quantity, BigDecimal amount, LocalDateTime createdAt) {}

    /** 计价规则：纯函数，输入相同则输出相同 */
    public static final class PricingPolicy {
        static final LocalTime EVENING_START = LocalTime.of(20, 0), EVENING_END = LocalTime.of(22, 0);

        public BigDecimal price(BigDecimal unitPrice, int quantity, boolean member, LocalTime at) {
            BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity));
            if (member) amount = amount.multiply(new BigDecimal("0.9"));
            boolean evening = !at.isBefore(EVENING_START) && at.isBefore(EVENING_END);
            if (evening && amount.compareTo(new BigDecimal("100")) >= 0) amount = amount.subtract(BigDecimal.TEN);
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
    }

    // 端口：由业务侧按自己的需要定义，实现放在外层
    public interface PriceCatalog { Optional<BigDecimal> unitPrice(String sku); }
    public interface OrderRepository { void save(Order order); }
    public interface OrderNumbers { String next(LocalDate day); }

    public static final class UnknownSkuException extends RuntimeException {
        public UnknownSkuException(String sku) { super("unknown sku: " + sku); }
    }

    /** 应用服务：只负责编排，不含规则细节 */
    public static final class OrderCreation {
        private final PriceCatalog catalog;
        private final OrderRepository repository;
        private final OrderNumbers numbers;
        private final PricingPolicy pricing;
        private final Clock clock;

        public OrderCreation(PriceCatalog catalog, OrderRepository repository, OrderNumbers numbers, PricingPolicy pricing, Clock clock) {
            this.catalog = catalog; this.repository = repository; this.numbers = numbers; this.pricing = pricing; this.clock = clock;
        }

        public Order create(OrderRequest req) {
            BigDecimal unitPrice = catalog.unitPrice(req.sku()).orElseThrow(() -> new UnknownSkuException(req.sku()));
            LocalDateTime now = LocalDateTime.now(clock);
            BigDecimal amount = pricing.price(unitPrice, req.quantity(), req.member(), now.toLocalTime());
            Order order = new Order(numbers.next(now.toLocalDate()), req.userId(), req.sku(), req.quantity(), amount, now);
            repository.save(order);
            return order;
        }
    }
}
