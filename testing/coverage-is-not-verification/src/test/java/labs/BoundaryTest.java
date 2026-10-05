package labs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 在典型值之外，补上每个分界点两侧的值。 */
class BoundaryTest {
    @Test
    void freeShippingThreshold() {
        assertEquals(800, ShippingFee.fee(9899, 500, false));
        assertEquals(0, ShippingFee.fee(9900, 500, false));
        assertEquals(0, ShippingFee.fee(20000, 500, false));
    }

    @Test
    void zeroAmountIsValid() {
        assertEquals(800, ShippingFee.fee(0, 500, false));
    }

    @Test
    void firstKilogramThreshold() {
        assertEquals(800, ShippingFee.fee(5000, 1, false));
        assertEquals(800, ShippingFee.fee(5000, 1000, false));
        assertEquals(1000, ShippingFee.fee(5000, 1001, false));
    }

    @Test
    void extraWeightRoundsUp() {
        assertEquals(1000, ShippingFee.fee(5000, 1500, false));
        assertEquals(1200, ShippingFee.fee(5000, 1501, false));
        assertEquals(1200, ShippingFee.fee(5000, 2000, false));
    }

    @Test
    void memberDiscount() {
        assertEquals(640, ShippingFee.fee(5000, 500, true));
        assertEquals(960, ShippingFee.fee(5000, 2000, true));
    }

    @Test
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> ShippingFee.fee(-1, 500, false));
        assertThrows(IllegalArgumentException.class, () -> ShippingFee.fee(5000, 0, false));
        assertThrows(IllegalArgumentException.class, () -> ShippingFee.fee(5000, -1, false));
    }
}
