package labs;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 需求里有、代码里没有写的规则：单件超过 30 公斤不承运。 */
class MissingRequirementTest {
    @Test
    void rejectsOverweightParcel() {
        assertThrows(IllegalArgumentException.class, () -> ShippingFee.fee(5000, 30001, false));
    }
}
