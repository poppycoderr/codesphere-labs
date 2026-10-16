package lab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 规则写成例子表；显示名直接说出是哪一条例子失败了 */
class ShippingRuleTest {
    @ParameterizedTest(name = "{0} 公斤 → 运费 {1}（{2}）")
    @CsvSource({
            "9, 10, 不满 10 公斤",
            "10, 20, 恰好 10 公斤",
            "11, 20, 超过 10 公斤"
    })
    void fee(int kg, int expected, String why) { assertThat(Shop.shippingFee(kg)).isEqualTo(expected); }

    @ParameterizedTest
    @CsvSource({ "9, 10", "10, 20", "11, 20" })
    void feeWithoutNames(int kg, int expected) { assertThat(Shop.shippingFee(kg)).isEqualTo(expected); }
}
