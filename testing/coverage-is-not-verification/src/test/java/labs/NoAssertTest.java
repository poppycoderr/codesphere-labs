package labs;

import org.junit.jupiter.api.Test;

/** 每条路径都调用到了，但没有检查任何结果。 */
class NoAssertTest {
    @Test
    void freeShipping() {
        ShippingFee.fee(20000, 500, false);
    }

    @Test
    void firstKilogram() {
        ShippingFee.fee(5000, 500, false);
    }

    @Test
    void extraWeightForMember() {
        ShippingFee.fee(5000, 2000, true);
    }

    @Test
    void invalidInput() {
        try {
            ShippingFee.fee(-1, 500, false);
        } catch (IllegalArgumentException expected) {
            // 只要不是别的异常就算通过
        }
        try {
            ShippingFee.fee(5000, 0, false);
        } catch (IllegalArgumentException expected) {
            // 同上
        }
    }
}
