package lab;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/** 订单摘要有两个字段算错：逐条断言只报第一个，分组断言一次报出全部 */
class GroupedAssertionTest {
    @Test void a_sequential() {
        var s = Shop.summary();
        assertEquals("alice", s.customer());
        assertEquals(new BigDecimal("100.00"), s.total());
        assertEquals("PAID", s.status());
    }
    @Test void b_assertAll() {
        var s = Shop.summary();
        assertAll("订单摘要",
                () -> assertEquals("alice", s.customer(), "customer"),
                () -> assertEquals(new BigDecimal("100.00"), s.total(), "total"),
                () -> assertEquals("PAID", s.status(), "status"));
    }
    @Test void c_softAssertions() {
        var s = Shop.summary();
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(s.customer()).as("customer").isEqualTo("alice");
        soft.assertThat(s.total()).as("total").isEqualByComparingTo("100.00");
        soft.assertThat(s.status()).as("status").isEqualTo("PAID");
        soft.assertAll();
    }
}
