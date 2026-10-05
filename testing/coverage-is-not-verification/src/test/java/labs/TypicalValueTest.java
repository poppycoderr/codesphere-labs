package labs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 每条路径取一个典型值并检查结果，没有取分界点上的值。 */
class TypicalValueTest {
    @Test
    void freeShipping() {
        assertEquals(0, ShippingFee.fee(20000, 500, false));
    }

    @Test
    void firstKilogram() {
        assertEquals(800, ShippingFee.fee(5000, 500, false));
    }

    @Test
    void extraWeight() {
        assertEquals(1200, ShippingFee.fee(5000, 2000, false));
    }

    @Test
    void extraWeightForMember() {
        assertEquals(960, ShippingFee.fee(5000, 2000, true));
    }

    @Test
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> ShippingFee.fee(-1, 500, false));
        assertThrows(IllegalArgumentException.class, () -> ShippingFee.fee(5000, 0, false));
    }
}
