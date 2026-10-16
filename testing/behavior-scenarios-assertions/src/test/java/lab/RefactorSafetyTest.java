package lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 计价从第一版重构到第二版，行为不变：测行为的测试两版都通过，测内部调用次数的测试在第二版失败 */
class RefactorSafetyTest {
    static final List<Shop.Line> LINES = List.of(new Shop.Line("A", new BigDecimal("10.00"), 2), new Shop.Line("B", new BigDecimal("5.00"), 1), new Shop.Line("C", new BigDecimal("1.00"), 3));
    Shop.TaxService tax() { Shop.TaxService t = mock(Shop.TaxService.class); when(t.rate()).thenReturn(new BigDecimal("0.10")); return t; }

    @Test void behavior_v1() { assertThat(Shop.totalV1(LINES, tax())).isEqualByComparingTo("30.80"); }
    @Test void behavior_v2() { assertThat(Shop.totalV2(LINES, tax())).isEqualByComparingTo("30.80"); }
    @Test void interaction_v1() { var t = tax(); Shop.totalV1(LINES, t); verify(t, times(3)).rate(); }
    @Test void interaction_v2() { var t = tax(); Shop.totalV2(LINES, t); verify(t, times(3)).rate(); }
}
