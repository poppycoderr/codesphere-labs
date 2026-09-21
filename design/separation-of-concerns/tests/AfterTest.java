import after.Ordering.*;
import after.Ordering.Order;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PricingPolicyTest {
    final PricingPolicy pricing = new PricingPolicy();
    final LocalTime noon = LocalTime.of(12, 0), evening = LocalTime.of(20, 30);

    @Test void 非会员按原价() { assertEquals(new BigDecimal("160.00"), pricing.price(new BigDecimal("80"), 2, false, noon)); }
    @Test void 会员九折() { assertEquals(new BigDecimal("144.00"), pricing.price(new BigDecimal("80"), 2, true, noon)); }
    @Test void 晚间满一百减十() { assertEquals(new BigDecimal("134.00"), pricing.price(new BigDecimal("80"), 2, true, evening)); }
    @Test void 晚间不足一百不减() { assertEquals(new BigDecimal("72.00"), pricing.price(new BigDecimal("80"), 1, true, evening)); }
    @Test void 二十二点整不再优惠() { assertEquals(new BigDecimal("144.00"), pricing.price(new BigDecimal("80"), 2, true, LocalTime.of(22, 0))); }
}

class OrderCreationTest {
    final List<Order> saved = new ArrayList<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-09-21T12:30:00Z"), ZoneOffset.UTC);
    final OrderCreation creation = new OrderCreation(
            sku -> sku.equals("A1") ? Optional.of(new BigDecimal("80.00")) : Optional.empty(),
            saved::add,
            day -> "ORD-" + day + "-000001",
            new PricingPolicy(), clock);

    @Test void 保存的订单包含确定的编号时间和金额() {
        Order o = creation.create(new OrderRequest("u1", "A1", 2, true));
        assertEquals("ORD-2026-09-21-000001", o.orderNo());
        assertEquals(LocalDateTime.of(2026, 9, 21, 12, 30), o.createdAt());
        assertEquals(new BigDecimal("144.00"), o.amount());
        assertEquals(List.of(o), saved);
    }
    @Test void 未知商品不保存() {
        assertThrows(UnknownSkuException.class, () -> creation.create(new OrderRequest("u1", "B9", 1, false)));
        assertTrue(saved.isEmpty());
    }
    @Test void 数量越界在构造请求时就被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new OrderRequest("u1", "A1", 100, false));
    }
}
